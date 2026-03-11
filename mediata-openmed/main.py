"""OpenMed Terminology Service.

A lightweight FastAPI service that infers SNOMED-searchable terminology
for dataset column names and their values using a biomedical NER model
(d4data/biomedical-ner-all).  When the transformer model cannot be loaded
(e.g. no network at first run), the service transparently falls back to
simple camelCase / snake_case text normalisation so it always returns
a usable search term.
"""

import logging
import re
import time
from typing import Dict, List, Optional

from fastapi import FastAPI
from pydantic import BaseModel

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger("openmed")

# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------
app = FastAPI(
    title="OpenMed Terminology Service",
    description=(
        "Infers biomedical terminology search terms for dataset columns and values "
        "using a biomedical NER model (d4data/biomedical-ner-all) with a text "
        "normalisation fallback."
    ),
    version="1.0.0",
)

# ---------------------------------------------------------------------------
# Model management
# ---------------------------------------------------------------------------
MODEL_NAME = "d4data/biomedical-ner-all"

# Minimum NER confidence score to accept an entity extraction.
# Below this threshold the service falls back to text normalisation.
# Raising the value increases precision at the cost of recall.
MIN_CONFIDENCE_THRESHOLD: float = 0.50

_pipeline = None          # set to a callable on success, or "fallback" sentinel


def _load_model_once():
    """Load the HuggingFace NER pipeline (idempotent; thread-safe for the
    single startup call pattern FastAPI uses)."""
    global _pipeline
    if _pipeline is not None:
        return _pipeline

    try:
        from transformers import pipeline as hf_pipeline

        logger.info("[OpenMed] Loading biomedical NER model: %s", MODEL_NAME)
        t0 = time.time()
        _pipeline = hf_pipeline(
            "ner",
            model=MODEL_NAME,
            aggregation_strategy="first",   # "first" merges BPE sub-tokens into whole words
            device=-1,          # CPU only – avoids GPU dependency in tests
        )
        logger.info("[OpenMed] Model loaded in %.1fs", time.time() - t0)

    except (ImportError, OSError, RuntimeError, EnvironmentError) as exc:
        logger.warning(
            "[OpenMed] Could not load transformer model (%s: %s). "
            "Using text-normalisation fallback.",
            type(exc).__name__, exc,
        )
        _pipeline = "fallback"   # sentinel – means "use normalise() only"
    except Exception as exc:  # noqa: BLE001
        # Broad catch is intentional: HuggingFace / PyTorch can surface many
        # exception types at model-load time (CUDA init errors, tokenizer
        # failures, hub auth errors, etc.) that cannot all be enumerated here.
        # The service must always start and fall back gracefully.
        logger.warning(
            "[OpenMed] Unexpected error loading model (%s: %s). "
            "Using text-normalisation fallback.",
            type(exc).__name__, exc,
        )
        _pipeline = "fallback"

    return _pipeline


# ---------------------------------------------------------------------------
# Request / response schemas
# ---------------------------------------------------------------------------

class ColumnInput(BaseModel):
    col_key: str
    values: List[str] = []


class BatchRequest(BaseModel):
    columns: List[ColumnInput]


class ColumnTerms(BaseModel):
    col_key: str
    col_search_term: str
    value_terms: Dict[str, str] = {}


class BatchResponse(BaseModel):
    columns: List[ColumnTerms]


# --- Description schemas ---

class ValueDescInput(BaseModel):
    v: str
    terminology_label: Optional[str] = None
    min: Optional[str] = None   # numeric range lower bound (from ValueSpec.min)
    max: Optional[str] = None   # numeric range upper bound (from ValueSpec.max)


class ColumnDescInput(BaseModel):
    col_key: str
    terminology_label: Optional[str] = None
    values: List[ValueDescInput] = []


class DescribeBatchRequest(BaseModel):
    columns: List[ColumnDescInput]


class ValueDesc(BaseModel):
    v: str
    d: str


class ColumnDesc(BaseModel):
    col_key: str
    col_desc: str
    values: List[ValueDesc] = []


class DescribeBatchResponse(BaseModel):
    columns: List[ColumnDesc]


# ---------------------------------------------------------------------------
# Inference helpers
# ---------------------------------------------------------------------------

def _normalise(text: str) -> str:
    """Split camelCase / snake_case tokens, lowercase and strip punctuation."""
    s = re.sub(r"([a-z])([A-Z])", r"\1 \2", text)   # camelCase → camel Case
    s = re.sub(r"[_\-]+", " ", s)                    # snake_case → snake case
    s = re.sub(r"\s+", " ", s)
    return s.strip().lower()


def _extract_label(terminology: str) -> str:
    """Extract a human-readable label from a terminology string.

    Handles three formats produced by the Snowstorm lookup pipeline:
    - New:    ``"Diabetes mellitus | 73211009"``  → ``"Diabetes mellitus"``
    - Legacy: ``"73211009|Diabetes mellitus"``     → ``"Diabetes mellitus"``
    - Plain:  ``"Hypertension"``                   → ``"Hypertension"``
    - Empty / None                                 → ``""``
    """
    t = (terminology or "").strip()
    if not t:
        return ""
    # New format: "label | code"
    sep = t.rfind(" | ")
    if sep > 0:
        label = t[:sep].strip()
        if label:
            return label
    # Legacy format: "code|label"
    bar = t.find("|")
    if 0 < bar < len(t) - 1:
        right = t[bar + 1:].strip()
        if right:
            return right
    return t


# ---------------------------------------------------------------------------
# Generic-column detection
# ---------------------------------------------------------------------------

_GENERIC_COL_RE = re.compile(
    r"^(col(?:umn)?|var(?:iable)?|field|attr(?:ribute)?|v|x|f)[\s_\-]?\d+$",
    re.IGNORECASE,
)


def _is_generic_column_name(name: str) -> bool:
    """Return True when *name* is a generic placeholder such as column_1 or var_2."""
    return bool(_GENERIC_COL_RE.match(name.strip()))


def _infer_col_from_values(col_key: str, values: List[str], pipe) -> str:
    """Infer a meaningful column search term when the column name is generic.

    Tries NER on the first few non-numeric, non-trivial values.  If NER produces
    a term that is clearly better than text normalisation, use it.  Otherwise
    falls back to normalising the column key itself.
    """
    candidates = [
        v.strip()
        for v in (values or [])
        if v and v.strip() and not re.match(r"^-?\d+(\.\d+)?$", v.strip())
        and len(v.strip()) >= 3
    ][:3]
    for candidate in candidates:
        norm = _normalise(candidate)
        term = _infer_term(candidate, pipe)
        alpha = sum(1 for c in term if c.isalpha())
        if alpha >= 2 and term != norm:
            logger.debug(
                "[OpenMed] Generic col '%s' inferred from value '%s' → '%s'",
                col_key,
                candidate,
                term,
            )
            return term
    return _normalise(col_key)


def _infer_term(text: str, pipe) -> str:
    """Return the best medical search term for *text*.

    Uses the NER pipeline when available and the entity confidence meets
    ``MIN_CONFIDENCE_THRESHOLD``.  Falls back to text normalisation otherwise
    so the caller always receives a non-empty, meaningful search phrase.
    """
    if not text or not text.strip():
        return text or ""

    normalised = _normalise(text)

    if pipe is None or pipe == "fallback":
        return normalised

    try:
        entities = pipe(text.strip()[:256])
        if entities:
            # Pick the highest-confidence entity.
            best = max(entities, key=lambda e: e.get("score", 0.0))
            score: float = best.get("score", 0.0)

            if score < MIN_CONFIDENCE_THRESHOLD:
                logger.debug(
                    "[OpenMed] Low-confidence NER for '%s' (score=%.3f < %.3f); "
                    "using text normalisation fallback",
                    text[:40],
                    score,
                    MIN_CONFIDENCE_THRESHOLD,
                )
                return normalised

            word = (best.get("word") or "").strip()
            # Reject artefacts: word must have at least 2 alphabetic characters.
            alpha_chars = sum(1 for c in word if c.isalpha())
            if word and alpha_chars >= 2:
                logger.debug(
                    "[OpenMed] NER '%s' → '%s' (score=%.3f)",
                    text[:40],
                    word,
                    score,
                )
                return word

            logger.debug(
                "[OpenMed] NER word '%s' rejected (alpha_chars=%d); "
                "using normalisation fallback",
                word,
                alpha_chars,
            )
    except (ValueError, RuntimeError) as exc:
        logger.warning("[OpenMed] NER pipeline error for text='%s': %s", text[:40], exc)
    except Exception as exc:  # noqa: BLE001
        # Broad catch is intentional: the aggregated NER pipeline can surface
        # many unexpected exception types depending on the model, tokeniser and
        # aggregation strategy.  We always fall back to text normalisation.
        logger.warning("[OpenMed] Unexpected NER error for text='%s': %s", text[:40], exc)

    return normalised


def _infer_value_term(value: str, col_search_term: str, pipe) -> str:
    """Infer the best search term for a column *value*, using column context when needed.

    Rules (in priority order):

    1. **Numeric or very-short values** (< 3 non-space characters): combine with the
       column search term so Snowstorm can find scale-specific concepts.
       e.g.  col='Toilet', value='0'   →  'toilet 0'
             col='BarthelIndex', value='5' →  'barthel index 5'

    2. **Fallback mode** (NER model not loaded): return the normalised value directly.
       Long medical terms like "hypertension" are self-identifying in Snowstorm and
       must not be contaminated with column context when NER is unavailable.

    3. **Model mode – multi-word values** where NER gives the same result as normalisation
       (i.e. the value is not a recognised medical entity): enrich with column context.
       e.g.  col='Education', value='High school' (NER: no entity) →  'high school education'

    4. **All other values**: use the NER result as-is.

    The result is always a plain-text search phrase, never a SNOMED code.
    """
    v = (value or "").strip()
    if not v:
        return v

    col_ctx = (col_search_term or "").strip().lower()

    # Rule 1 – numeric or very short: always prepend column context
    is_numeric = bool(re.match(r"^-?\d+(\.\d+)?$", v))
    is_very_short = len(v.replace(" ", "")) < 3
    if is_numeric or is_very_short:
        if col_ctx:
            result = f"{col_ctx} {v}"
            logger.debug(
                "[OpenMed] Short/numeric value '%s' enriched with col context → '%s'",
                v,
                result,
            )
            return result
        return _normalise(v)

    # Rule 2 – fallback mode: return plain normalised form; do not add column context.
    # Medical terms are self-identifying in Snowstorm; contaminating them with the
    # column name would produce multi-word phrases that rarely match any concept
    # (e.g. "hypertension diagnosis" instead of "hypertension").
    if pipe is None or pipe == "fallback":
        return _normalise(v)

    # Rule 3/4 – model IS loaded: run NER, enrich only when NER gives no improvement
    normalised = _normalise(v)
    term = _infer_term(v, pipe)

    # Enrich with column context only for phrases of 3+ words where NER couldn't find
    # a specific entity.  Two-word medical terms ("diabetes mellitus", "myocardial
    # infarction", "high school") are self-identifying in Snowstorm; contaminating
    # them with the column name hurts recall.
    if term == normalised and col_ctx and len(v.split()) >= 3:
        enriched = f"{normalised} {col_ctx}"
        logger.debug(
            "[OpenMed] Multi-word value '%s' enriched with col context → '%s'",
            v,
            enriched,
        )
        return enriched

    return term or normalised


# ---------------------------------------------------------------------------
# Application lifecycle
# ---------------------------------------------------------------------------

@app.on_event("startup")
async def startup_event():
    logger.info("[OpenMed] Service starting up – pre-loading model …")
    _load_model_once()
    logger.info("[OpenMed] Ready.")


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/health")
async def health():
    """Liveness / readiness probe."""
    pipe = _pipeline
    model_loaded = pipe is not None and pipe != "fallback"
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "model_loaded": model_loaded,
        "mode": "ner" if model_loaded else "fallback",
    }


@app.post("/infer_batch", response_model=BatchResponse)
async def infer_batch(request: BatchRequest):
    """Infer SNOMED-searchable terminology for a batch of columns.

    For each ``ColumnInput``:
    - Suggest a ``col_search_term`` for the column header itself.
    - Suggest a search term for every distinct value.

    Uses a biomedical NER model when available; falls back to camelCase /
    snake_case normalisation otherwise.
    """
    pipe = _load_model_once()
    t0 = time.time()
    logger.info("[OpenMed] /infer_batch – %d columns", len(request.columns))

    results: List[ColumnTerms] = []
    for col in request.columns:
        col_key = (col.col_key or "").strip()
        if not col_key:
            logger.warning("[OpenMed] Skipping column with empty col_key")
            continue

        # For generic placeholder names (column_1, var_2, …) try to infer from values.
        if _is_generic_column_name(col_key):
            col_term = _infer_col_from_values(col_key, col.values, pipe) or col_key
        else:
            col_term = _infer_term(col_key, pipe) or col_key

        value_terms: Dict[str, str] = {}
        for raw_val in col.values:
            v = (raw_val or "").strip()
            if v:
                # Always pass the column search term so numeric / short values
                # receive meaningful context (e.g. "toilet 0", "barthel index 5").
                value_terms[v] = _infer_value_term(v, col_term, pipe) or v

        results.append(
            ColumnTerms(
                col_key=col_key,
                col_search_term=col_term,
                value_terms=value_terms,
            )
        )

    elapsed = time.time() - t0
    logger.info("[OpenMed] Inferred %d column(s) in %.2fs", len(results), elapsed)
    return BatchResponse(columns=results)


# ---------------------------------------------------------------------------
# Description helpers
# ---------------------------------------------------------------------------

def _sentence(text: str, fallback: str = "field") -> str:
    """Capitalise and ensure the text ends with a full stop."""
    s = (text or "").strip() or fallback.strip() or "field"
    if s.startswith('"') and s.endswith('"') and len(s) >= 2:
        s = s[1:-1].strip()
    s = s[:1].upper() + s[1:] if s else s
    if s and s[-1] not in ".!?":
        s += "."
    return s


def _build_value_phrase(
    v: str,
    col_label: str,
    scale_min: Optional[float],
    scale_max: Optional[float],
) -> str:
    """Build a plain-language phrase for a numeric value using available context only.

    The phrase explicitly states **what the values are measuring** (the column's
    terminology label) so no hardcoded clinical-term lookup tables are needed.
    The NER model (or the phrase itself) then provides the description.

    The format ``"score {v} of {max} measuring {col_label}"`` gives the model
    the full context: the numeric value, the scale upper bound, and the clinical
    concept being measured.

    Examples
    --------
    col_label="Ability to use toilet", v="0", min=0, max=10
      → "score 0 of 10 measuring Ability to use toilet"

    col_label="Barthel index", v="50", min=0, max=100
      → "score 50 of 100 measuring Barthel index"

    col_label="pain level", v="3", no scale
      → "score 3 measuring pain level"
    """
    label = (col_label or "").strip()

    if scale_min is not None and scale_max is not None and scale_max > scale_min:
        max_str = str(int(scale_max)) if scale_max == int(scale_max) else str(scale_max)
        if label:
            return f"score {v} of {max_str} measuring {label}"
        return f"score {v} of {max_str}"

    if label:
        return f"score {v} measuring {label}"
    return f"value {v}"


@app.post("/describe_batch", response_model=DescribeBatchResponse)
async def describe_batch(request: DescribeBatchRequest):
    """Generate plain-language descriptions for column headers and their values.

    For each ``ColumnDescInput``:

    - **col_desc**: SNOMED terminology label when available; otherwise normalise
      the column key.
    - **values[i].d**:
        - If a SNOMED terminology label is provided for the value, use it directly.
        - If the value is numeric, build a context phrase that explicitly states
          what the values are measuring:
          ``"score {v} of {max} measuring {col_label}"``
          No hardcoded clinical-term lookup tables are used; the description is
          derived entirely from the column's terminology label, the numeric value,
          and the optional scale range (min/max).
        - If the value is text, run NER with column context to extract the best
          biomedical term; fall back to text normalisation.
    """
    pipe = _load_model_once()
    t0 = time.time()
    logger.info("[OpenMed] /describe_batch – %d columns", len(request.columns))

    results: List[ColumnDesc] = []
    for col in request.columns:
        col_key = (col.col_key or "").strip()
        if not col_key:
            continue

        # Column description: extract plain label from SNOMED string, then normalise col_key
        term_label = _extract_label(col.terminology_label or "")
        col_desc_raw = term_label if term_label else _normalise(col_key)
        col_desc = _sentence(col_desc_raw, col_key)

        value_descs: List[ValueDesc] = []
        for val_info in (col.values or []):
            v = (val_info.v or "").strip()
            if not v:
                continue

            val_term = _extract_label(val_info.terminology_label or "")
            if val_term:
                # SNOMED label available – use it directly.
                d = _sentence(val_term, v)

            elif re.match(r"^-?\d+(\.\d+)?$", v):
                # Numeric value: build a contextual phrase that states what the
                # value is measuring.  No hardcoded clinical terms are used.
                scale_min: Optional[float] = None
                scale_max: Optional[float] = None
                try:
                    if val_info.min is not None:
                        scale_min = float(val_info.min)
                    if val_info.max is not None:
                        scale_max = float(val_info.max)
                except (ValueError, TypeError):
                    pass
                phrase = _build_value_phrase(v, col_desc_raw, scale_min, scale_max)
                d = _sentence(phrase, v)

            else:
                # Text value: run NER with column context so the model knows
                # what these values are measuring.
                context_phrase = f"{v} {col_desc_raw}" if col_desc_raw else v
                inferred = _infer_term(context_phrase, pipe)
                # Keep only the value-level NER result (not the full context phrase)
                # unless NER returned nothing useful.
                if not inferred or inferred == _normalise(context_phrase):
                    inferred = _infer_term(v, pipe) or _normalise(v)
                d = _sentence(inferred, v)

            value_descs.append(ValueDesc(v=v, d=d))

        results.append(ColumnDesc(
            col_key=col_key,
            col_desc=col_desc,
            values=value_descs,
        ))

    elapsed = time.time() - t0
    logger.info("[OpenMed] Described %d column(s) in %.2fs", len(results), elapsed)
    return DescribeBatchResponse(columns=results)
