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


# ---------------------------------------------------------------------------
# Inference helpers
# ---------------------------------------------------------------------------

def _normalise(text: str) -> str:
    """Split camelCase / snake_case tokens, lowercase and strip punctuation."""
    s = re.sub(r"([a-z])([A-Z])", r"\1 \2", text)   # camelCase → camel Case
    s = re.sub(r"[_\-]+", " ", s)                    # snake_case → snake case
    s = re.sub(r"\s+", " ", s)
    return s.strip().lower()


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
