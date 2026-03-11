"""Tests for the /describe_batch endpoint in mediata-openmed/main.py.

Runs against the actual FastAPI app (via httpx.AsyncClient / TestClient) so
the full routing, Pydantic validation and description-logic is exercised –
no mocking of the endpoint internals.

The NER model is intentionally NOT loaded during tests (the startup event
fires, but the HuggingFace download is skipped in CI because the model
isn't present).  The fallback text-normalisation path is therefore used,
which is deterministic and fast.
"""

import pytest
from fastapi.testclient import TestClient

# Import the app after setting an env-var so the model load is skipped
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

from main import app, _extract_label

client = TestClient(app)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _describe(columns: list) -> dict:
    """POST /describe_batch and return the parsed JSON."""
    resp = client.post("/describe_batch", json={"columns": columns})
    assert resp.status_code == 200, f"Unexpected status {resp.status_code}: {resp.text}"
    return resp.json()


# ---------------------------------------------------------------------------
# _extract_label unit tests
# ---------------------------------------------------------------------------

class TestExtractLabel:
    def test_new_format_returns_label(self):
        """'label | code' → just the label."""
        assert _extract_label("Diabetes mellitus | 73211009") == "Diabetes mellitus"

    def test_new_format_multi_word_label(self):
        assert _extract_label("Ability to use toilet | 284548004") == "Ability to use toilet"

    def test_legacy_format_returns_right_side(self):
        """'code|label' → label on the right."""
        assert _extract_label("73211009|Diabetes mellitus") == "Diabetes mellitus"

    def test_plain_text_returned_as_is(self):
        assert _extract_label("Hypertension") == "Hypertension"

    def test_empty_returns_empty(self):
        assert _extract_label("") == ""

    def test_none_returns_empty(self):
        assert _extract_label(None) == ""

    def test_whitespace_only_returns_empty(self):
        assert _extract_label("   ") == ""


# ---------------------------------------------------------------------------
# Basic structural tests
# ---------------------------------------------------------------------------

class TestDescribeBatchStructure:
    def test_empty_request_returns_empty_list(self):
        data = _describe([])
        assert data["columns"] == []

    def test_single_column_no_values(self):
        data = _describe([{"col_key": "Diagnosis", "values": []}])
        cols = data["columns"]
        assert len(cols) == 1
        assert cols[0]["col_key"] == "Diagnosis"
        assert cols[0]["col_desc"]  # non-empty
        assert cols[0]["values"] == []

    def test_column_with_empty_key_skipped(self):
        data = _describe([
            {"col_key": "", "values": []},
            {"col_key": "Gender", "values": []},
        ])
        keys = [c["col_key"] for c in data["columns"]]
        assert "" not in keys
        assert "Gender" in keys

    def test_multiple_columns_returned_in_order(self):
        data = _describe([
            {"col_key": "Age", "values": []},
            {"col_key": "Gender", "values": []},
            {"col_key": "Diagnosis", "values": []},
        ])
        keys = [c["col_key"] for c in data["columns"]]
        assert keys == ["Age", "Gender", "Diagnosis"]

    def test_value_entries_preserved(self):
        data = _describe([{
            "col_key": "Status",
            "values": [{"v": "Yes"}, {"v": "No"}],
        }])
        vals = {vd["v"]: vd["d"] for vd in data["columns"][0]["values"]}
        assert "Yes" in vals
        assert "No" in vals
        assert vals["Yes"]
        assert vals["No"]


# ---------------------------------------------------------------------------
# Sentence formatting
# ---------------------------------------------------------------------------

class TestSentenceFormatting:
    def test_col_desc_starts_with_uppercase(self):
        data = _describe([{"col_key": "bloodPressure", "values": []}])
        desc = data["columns"][0]["col_desc"]
        assert desc[0].isupper(), f"Expected uppercase start: {desc!r}"

    def test_col_desc_ends_with_period(self):
        data = _describe([{"col_key": "heartRate", "values": []}])
        desc = data["columns"][0]["col_desc"]
        assert desc.endswith("."), f"Expected period at end: {desc!r}"

    def test_value_desc_ends_with_period(self):
        data = _describe([{
            "col_key": "Pain",
            "values": [{"v": "mild"}, {"v": "severe"}],
        }])
        for vd in data["columns"][0]["values"]:
            assert vd["d"].endswith("."), f"Value desc {vd['d']!r} missing period"


# ---------------------------------------------------------------------------
# Terminology label in "label | code" format (SNOMED output from Java)
# ---------------------------------------------------------------------------

class TestTerminologyLabelExtraction:
    def test_snomed_format_label_used_for_col_desc(self):
        """When a 'label | code' SNOMED string is provided, only the label is used."""
        data = _describe([{
            "col_key": "diag_code",
            "terminology_label": "Hypertensive disorder | 38341003",
            "values": [],
        }])
        desc = data["columns"][0]["col_desc"]
        # Must contain 'Hypertensive disorder' and NOT the raw SNOMED code
        assert "38341003" not in desc, (
            f"SNOMED code must not appear in col_desc, got: {desc!r}"
        )
        assert "Hypertensive" in desc, (
            f"Expected label in col_desc, got: {desc!r}"
        )

    def test_snomed_format_label_used_for_value_desc(self):
        """Value terminology_label in 'label | code' format → label only in description."""
        data = _describe([{
            "col_key": "gender",
            "values": [
                {"v": "1", "terminology_label": "Male (finding) | 248153007"},
                {"v": "2", "terminology_label": "Female (finding) | 248152002"},
            ],
        }])
        vals = {vd["v"]: vd["d"] for vd in data["columns"][0]["values"]}
        assert "248153007" not in vals["1"], (
            f"SNOMED code must not appear in value desc, got: {vals['1']!r}"
        )
        assert "Male" in vals["1"] or "male" in vals["1"].lower(), (
            f"Expected 'Male' in value desc, got: {vals['1']!r}"
        )
        assert "Female" in vals["2"] or "female" in vals["2"].lower(), (
            f"Expected 'Female' in value desc, got: {vals['2']!r}"
        )

    def test_plain_terminology_label_used_directly(self):
        """Plain text (no pipe) in terminology_label is used directly."""
        data = _describe([{
            "col_key": "diag_code",
            "terminology_label": "Hypertensive disorder",
            "values": [],
        }])
        desc = data["columns"][0]["col_desc"]
        assert "Hypertensive" in desc, f"Expected label in col_desc, got: {desc!r}"


# ---------------------------------------------------------------------------
# CamelCase / snake_case normalisation (fallback path)
# ---------------------------------------------------------------------------

class TestFallbackNormalisation:
    def test_camel_case_column_normalised(self):
        data = _describe([{"col_key": "bloodPressure", "values": []}])
        desc = data["columns"][0]["col_desc"].lower()
        assert "blood" in desc and "pressure" in desc, (
            f"Expected 'blood pressure' in normalised desc, got: {desc!r}"
        )

    def test_snake_case_column_normalised(self):
        data = _describe([{"col_key": "heart_rate", "values": []}])
        desc = data["columns"][0]["col_desc"].lower()
        assert "heart" in desc and "rate" in desc, (
            f"Expected 'heart rate' in normalised desc, got: {desc!r}"
        )

    def test_text_value_normalised(self):
        data = _describe([{
            "col_key": "educationLevel",
            "values": [{"v": "primary_school"}],
        }])
        vd = data["columns"][0]["values"][0]
        assert vd["d"].lower().startswith("primary"), (
            f"Expected 'primary school' normalised, got: {vd['d']!r}"
        )


# ---------------------------------------------------------------------------
# Numeric / ordinal values
# ---------------------------------------------------------------------------

class TestNumericValues:
    def test_numeric_values_get_contextual_descs(self):
        """Numeric values should get non-empty descriptions that include the value itself."""
        data = _describe([{
            "col_key": "Score",
            "values": [{"v": "0"}, {"v": "1"}, {"v": "2"}],
        }])
        descs = [vd["d"] for vd in data["columns"][0]["values"]]
        assert all(d for d in descs), "Each numeric value should have a non-empty description"
        # Each description must contain the numeric value
        for vd in data["columns"][0]["values"]:
            assert vd["v"] in vd["d"], (
                f"Expected value {vd['v']!r} to appear in description, got: {vd['d']!r}"
            )

    def test_adl_column_descriptions_contain_value_and_label(self):
        """Toilet column: descriptions state what the values measure and include the value."""
        data = _describe([{
            "col_key": "Toilet",
            "terminology_label": "Ability to use toilet | 284548004",
            "values": [{"v": "0"}, {"v": "5"}, {"v": "10"}],
        }])
        descs = {vd["v"]: vd["d"] for vd in data["columns"][0]["values"]}
        # Each description must reference the value being described
        for val_str, desc in descs.items():
            assert val_str in desc, (
                f"Expected value {val_str!r} in description, got: {desc!r}"
            )
        # The column label (or a key part of it) should appear in the description
        assert any("toilet" in d.lower() for d in descs.values()), (
            "Expected 'toilet' to appear in at least one value description"
        )

    def test_adl_column_with_min_max_includes_scale_context(self):
        """Barthel item column (0-10) with min/max: descriptions state the scale context."""
        data = _describe([{
            "col_key": "ToiletBART1",
            "terminology_label": "Ability to use toilet | 284548004",
            "values": [
                {"v": "0",  "min": "0", "max": "10"},
                {"v": "5",  "min": "0", "max": "10"},
                {"v": "10", "min": "0", "max": "10"},
            ],
        }])
        descs = {vd["v"]: vd["d"] for vd in data["columns"][0]["values"]}
        # Each description must contain the numeric value and the scale maximum
        for val_str, desc in descs.items():
            assert val_str in desc, (
                f"Expected value {val_str!r} in description, got: {desc!r}"
            )
            assert "10" in desc, (
                f"Expected scale max '10' in description for {val_str!r}, got: {desc!r}"
            )
        # Descriptions should explicitly name what is being measured
        assert "toilet" in descs["0"].lower(), (
            f"Expected 'toilet' in description for 0, got: {descs['0']!r}"
        )

    def test_barthel_total_score_0_to_100_context(self):
        """Barthel total (0-100): descriptions state value, scale max and column label."""
        data = _describe([{
            "col_key": "TOTALBARTHEL",
            "terminology_label": "Barthel index | 273302005",
            "values": [
                {"v": "0",   "min": "0", "max": "100"},
                {"v": "50",  "min": "0", "max": "100"},
                {"v": "100", "min": "0", "max": "100"},
            ],
        }])
        descs = {vd["v"]: vd["d"] for vd in data["columns"][0]["values"]}
        # Each value's description includes the value itself and scale max
        assert "0" in descs["0"] and "100" in descs["0"], (
            f"Expected '0' and '100' in description for 0, got: {descs['0']!r}"
        )
        assert "100" in descs["100"], (
            f"Expected '100' in description for 100, got: {descs['100']!r}"
        )
        # SNOMED code must NOT appear; the label should
        col_desc = data["columns"][0]["col_desc"]
        assert "273302005" not in col_desc, (
            f"SNOMED code must not appear in col_desc, got: {col_desc!r}"
        )
        assert "Barthel" in col_desc, (
            f"Expected 'Barthel' in col_desc, got: {col_desc!r}"
        )
        # Descriptions should mention what is being measured
        assert "barthel" in descs["0"].lower(), (
            f"Expected 'barthel' in description for 0, got: {descs['0']!r}"
        )

    def test_bathing_binary_with_scale_context(self):
        """Binary Bathing column (0-5): descriptions include value, scale max and label."""
        data = _describe([{
            "col_key": "BathingBART1",
            "terminology_label": "Bathing | 284546000",
            "values": [
                {"v": "0", "min": "0", "max": "5"},
                {"v": "5", "min": "0", "max": "5"},
            ],
        }])
        descs = {vd["v"]: vd["d"] for vd in data["columns"][0]["values"]}
        assert "0" in descs["0"] and "5" in descs["0"], (
            f"Expected '0' and '5' in description for 0, got: {descs['0']!r}"
        )
        assert "5" in descs["5"], (
            f"Expected '5' in description for 5, got: {descs['5']!r}"
        )
        assert "bathing" in descs["0"].lower(), (
            f"Expected 'bathing' in description for 0, got: {descs['0']!r}"
        )


# ---------------------------------------------------------------------------
# Health endpoint (smoke test)
# ---------------------------------------------------------------------------

def test_health_endpoint():
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
