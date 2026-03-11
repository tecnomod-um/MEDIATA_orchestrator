"""Tests for the /describe_batch endpoint in mediata-openmed/main.py.

Runs against the actual FastAPI app (via httpx.AsyncClient / TestClient) so
the full routing, Pydantic validation and description-logic is exercised –
no mocking of the endpoint internals.

Both the NER and description models are forced to "fallback" mode so that
tests are deterministic and fast regardless of whether the model weights are
cached locally.  The fallback path uses text normalisation on the prompt
phrase built by the description helpers, which produces predictable output
("score 0 of 10 measuring ability to use toilet.", etc.).

The actual generative model output (Qwen2.5-0.5B-Instruct) is verified by
the Java integration test (MappingServiceReportTest) which runs against a
live OpenMed server with both models loaded.
"""

import pytest
from fastapi.testclient import TestClient

import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

# Force both pipelines to fallback BEFORE the TestClient fires the startup
# event – this prevents slow model downloads/loads in unit tests and keeps
# the test suite deterministic.
import main as _main_module
_main_module._pipeline = "fallback"
_main_module._describe_pipeline = "fallback"

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
        # Category phrase: "Primary school as a category of education level."
        # The value token must appear in the description
        assert "primary" in vd["d"].lower(), (
            f"Expected 'primary' in normalised category desc, got: {vd['d']!r}"
        )


# ---------------------------------------------------------------------------
# Numeric / ordinal values
# ---------------------------------------------------------------------------

class TestNumericValues:
    def test_numeric_values_get_contextual_descs(self):
        """Numeric values without scale bounds are treated as value codes – not 'scores'."""
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

    def test_numeric_without_scale_uses_value_phrase_not_score(self):
        """Numeric values without min/max must NOT say 'score' (could be category codes)."""
        data = _describe([{
            "col_key": "Gender",
            "values": [{"v": "1"}, {"v": "2"}],
        }])
        for vd in data["columns"][0]["values"]:
            assert vd["d"].lower().startswith("score") is False, (
                f"Value without scale bounds must not say 'score', got: {vd['d']!r}"
            )
            assert vd["v"] in vd["d"], (
                f"Expected value {vd['v']!r} in description, got: {vd['d']!r}"
            )

    def test_adl_column_without_scale_uses_neutral_phrase(self):
        """Toilet column without min/max → neutral value phrase, not score phrase."""
        data = _describe([{
            "col_key": "Toilet",
            "terminology_label": "Ability to use toilet | 284548004",
            "values": [{"v": "0"}, {"v": "5"}, {"v": "10"}],
        }])
        descs = {vd["v"]: vd["d"] for vd in data["columns"][0]["values"]}
        for val_str, desc in descs.items():
            assert val_str in desc, (
                f"Expected value {val_str!r} in description, got: {desc!r}"
            )
            assert desc.lower().startswith("score") is False, (
                f"Without scale bounds must not say 'score', got: {desc!r}"
            )
        # The column label should appear in the description
        assert any("toilet" in d.lower() for d in descs.values()), (
            "Expected 'toilet' to appear in at least one value description"
        )

    def test_adl_column_with_min_max_uses_score_phrase(self):
        """Barthel item (0-10) with min/max → score phrase including value, max and label."""
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
        """Barthel total (0-100): descriptions include value, scale max and column label."""
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
        assert "0" in descs["0"] and "100" in descs["0"], (
            f"Expected '0' and '100' in description for 0, got: {descs['0']!r}"
        )
        assert "100" in descs["100"], (
            f"Expected '100' in description for 100, got: {descs['100']!r}"
        )
        col_desc = data["columns"][0]["col_desc"]
        assert "273302005" not in col_desc, (
            f"SNOMED code must not appear in col_desc, got: {col_desc!r}"
        )
        assert "Barthel" in col_desc, (
            f"Expected 'Barthel' in col_desc, got: {col_desc!r}"
        )
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
# Categorical (text) values
# ---------------------------------------------------------------------------

class TestCategoricalValues:
    def test_text_categories_include_column_context(self):
        """Text values are treated as categories – column label appears in description."""
        data = _describe([{
            "col_key": "StrokeType",
            "terminology_label": "Stroke | 230690007",
            "values": [{"v": "Ischemic"}, {"v": "Hemorrhagic"}],
        }])
        for vd in data["columns"][0]["values"]:
            assert "stroke" in vd["d"].lower(), (
                f"Expected 'stroke' (column context) in category description, got: {vd['d']!r}"
            )

    def test_text_categories_sentence_format(self):
        """Category descriptions must be valid sentences."""
        data = _describe([{
            "col_key": "Status",
            "values": [{"v": "Yes"}, {"v": "No"}],
        }])
        for vd in data["columns"][0]["values"]:
            assert vd["d"][0].isupper(), f"Must start uppercase: {vd['d']!r}"
            assert vd["d"].endswith("."), f"Must end with period: {vd['d']!r}"

    def test_text_categories_no_hardcoded_terms(self):
        """Category descriptions are derived from context, not hardcoded lookup tables."""
        data = _describe([{
            "col_key": "educationLevel",
            "values": [{"v": "primary_school"}, {"v": "university"}],
        }])
        descs = {vd["v"]: vd["d"].lower() for vd in data["columns"][0]["values"]}
        # 'education' (from col_key) should appear in the descriptions
        assert "education" in descs["primary_school"], (
            f"Expected 'education' in description for 'primary_school', got: {descs['primary_school']!r}"
        )
        assert "education" in descs["university"], (
            f"Expected 'education' in description for 'university', got: {descs['university']!r}"
        )

    def test_boolean_text_categories_include_context(self):
        """Yes/No values include column context so they are distinguishable."""
        data = _describe([{
            "col_key": "HasDiabetes",
            "values": [{"v": "Yes"}, {"v": "No"}],
        }])
        descs = {vd["v"]: vd["d"].lower() for vd in data["columns"][0]["values"]}
        assert "diabetes" in descs["Yes"] or "has diabetes" in descs["Yes"], (
            f"Expected column context in 'Yes' description, got: {descs['Yes']!r}"
        )
        assert "diabetes" in descs["No"] or "has diabetes" in descs["No"], (
            f"Expected column context in 'No' description, got: {descs['No']!r}"
        )


# ---------------------------------------------------------------------------
# Health endpoint (smoke test)
# ---------------------------------------------------------------------------

def test_health_endpoint():
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    # Both model keys must be present in the new dual-model response
    assert "ner_model" in body
    assert "describe_model" in body
