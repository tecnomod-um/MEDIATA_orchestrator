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
            aggregation_strategy="simple",
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

        col_term = _infer_term(col_key, pipe) or col_key

        value_terms: Dict[str, str] = {}
        for raw_val in col.values:
            v = (raw_val or "").strip()
            if v:
                value_terms[v] = _infer_term(v, pipe) or v

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
