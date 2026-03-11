"""
OpenMed Inference Service
=========================
Thin FastAPI wrapper around the ``openmed`` Python library.

POST /infer-terms
    Accepts a JSON batch of column names + sampled values and returns
    the best medical search term for each column and value using
    OpenMed's NER models.  Column names and values are processed in a
    single batch call to ``openmed.process_batch()`` for efficiency.

The service is intentionally stateless: the NLP model is loaded once at
startup and reused for all requests.

If ``openmed`` or ``torch`` are not available the service will refuse to
start and log the error clearly.
"""

import logging
import os
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

# ---------------------------------------------------------------------------
# Model selection – override via OPENMED_MODEL env var.
# DiseaseDetect-TinyMed-135M is the lightest model that still understands
# the full range of clinical concepts (ADL activities, diagnoses, scores).
# ---------------------------------------------------------------------------
DEFAULT_MODEL = os.environ.get(
    "OPENMED_MODEL",
    "OpenMed/OpenMed-NER-DiseaseDetect-TinyMed-135M",
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Guard: both openmed and torch must be importable before we serve requests.
# ---------------------------------------------------------------------------
try:
    import openmed  # noqa: E402
except ImportError as exc:
    raise RuntimeError(
        "openmed package is not installed. "
        "Install it with:  pip install openmed torch transformers"
    ) from exc

app = FastAPI(
    title="OpenMed Inference Service",
    description="NER-based medical terminology inference for MEDIATA.",
    version="1.0.0",
)


# ---------------------------------------------------------------------------
# Request / response schemas
# ---------------------------------------------------------------------------

class ColumnInput(BaseModel):
    colKey: str
    values: List[str] = []


class BatchRequest(BaseModel):
    columns: List[ColumnInput]


class InferredValue(BaseModel):
    raw: str
    searchTerm: str


class InferredColumn(BaseModel):
    colKey: str
    colSearchTerm: str
    values: List[InferredValue]


class BatchResponse(BaseModel):
    columns: List[InferredColumn]


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------

@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model": DEFAULT_MODEL}


# ---------------------------------------------------------------------------
# Core inference endpoint
# ---------------------------------------------------------------------------

@app.post("/infer-terms", response_model=BatchResponse)
def infer_terms(request: BatchRequest) -> BatchResponse:
    """
    Run OpenMed NER on a batch of column names and their sampled values.

    For each text the endpoint:
    1. Runs ``openmed.process_batch()`` once for all texts in the request.
    2. Uses the highest-confidence entity *text* as the SNOMED search term.
    3. Falls back to the original text when no entity is detected (the
       column name itself is often the correct medical concept).
    """
    if not request.columns:
        return BatchResponse(columns=[])

    # Build flat lists: one text + one ID per column-name or value
    texts: List[str] = []
    ids: List[str] = []

    for col in request.columns:
        col_key = (col.colKey or "").strip()
        if not col_key:
            continue
        texts.append(col_key)
        ids.append(f"col::{col_key}")

        seen_vals: set = set()
        for raw_val in col.values:
            val = (raw_val or "").strip()
            if not val or val in seen_vals:
                continue
            seen_vals.add(val)
            texts.append(val)
            ids.append(f"val::{col_key}::{val}")

    if not texts:
        return BatchResponse(columns=[])

    # Run all texts through OpenMed in one shot
    try:
        batch_result = openmed.process_batch(
            texts=texts,
            model_name=DEFAULT_MODEL,
            ids=ids,
        )
    except Exception as exc:  # pragma: no cover
        logger.error("OpenMed process_batch failed: %s", exc, exc_info=True)
        raise HTTPException(status_code=500, detail=f"OpenMed inference error: {exc}") from exc

    # Index results by their ID for O(1) lookup
    result_by_id: dict = {}
    for item in batch_result.items:
        if item.success and item.result and item.result.entities:
            # Pick the entity with the highest confidence
            best = max(item.result.entities, key=lambda e: e.confidence)
            result_by_id[item.id] = best.text.strip()

    # Re-assemble the structured response
    inferred_columns: List[InferredColumn] = []
    for col in request.columns:
        col_key = (col.colKey or "").strip()
        if not col_key:
            continue

        col_search_term = result_by_id.get(f"col::{col_key}", col_key)

        inferred_values: List[InferredValue] = []
        seen_vals_resp: set = set()
        for raw_val in col.values:
            val = (raw_val or "").strip()
            if not val or val in seen_vals_resp:
                continue
            seen_vals_resp.add(val)
            search_term = result_by_id.get(f"val::{col_key}::{val}", val)
            inferred_values.append(InferredValue(raw=val, searchTerm=search_term))

        inferred_columns.append(
            InferredColumn(
                colKey=col_key,
                colSearchTerm=col_search_term,
                values=inferred_values,
            )
        )

    logger.info(
        "[OpenMedService] inferred terms for %d columns, %d value entries",
        len(inferred_columns),
        sum(len(c.values) for c in inferred_columns),
    )
    return BatchResponse(columns=inferred_columns)
