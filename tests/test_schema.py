import json

import pytest
from pydantic import ValidationError

from app.schemas.sds import SDS_JSON_SCHEMA, SDSDocument, SDSSection
from tests.factories import minimal_sds_payload


def test_sds_document_instantiates_with_minimal_data():
    doc = SDSDocument.model_validate(minimal_sds_payload())

    assert doc.schema_version == "1.0"
    assert doc.section_4_first_aid.section_title_ja == "応急措置"
    assert doc.extraction_notes is None


def test_sds_document_instantiates_with_full_data():
    payload = minimal_sds_payload()
    payload["section_1_product_and_company"] = {
        "product_name": "テスト製品",
        "manufacturer": {"company_name": "テスト株式会社", "phone": "03-0000-0000"},
        "raw_text": "1. 化学品及び会社情報...",
    }
    payload["section_2_hazards_identification"] = {
        "ghs_classifications": [{"hazard_class": "急性毒性(経口)", "category": "区分3"}],
        "signal_word": "危険",
        "hazard_statements": ["飲み込むと有毒"],
        "pictograms": ["GHS06"],
    }
    payload["section_3_composition"] = {
        "mixture_or_substance": "mixture",
        "ingredients": [
            {"substance_name": "エタノール", "cas_number": "64-17-5", "concentration": "10~20%"}
        ],
    }
    payload["extraction_notes"] = "セクション14は判読不能のため空欄"

    doc = SDSDocument.model_validate(payload)

    assert doc.section_1_product_and_company.manufacturer.company_name == "テスト株式会社"
    assert doc.section_2_hazards_identification.pictograms == ["GHS06"]
    assert doc.section_3_composition.ingredients[0].concentration == "10~20%"


def test_model_validate_json_roundtrip():
    payload = minimal_sds_payload()
    doc = SDSDocument.model_validate(payload)

    reparsed = SDSDocument.model_validate_json(doc.model_dump_json())
    assert reparsed == doc


def test_unknown_field_is_rejected():
    payload = minimal_sds_payload()
    payload["unexpected_field"] = "should not be allowed"

    with pytest.raises(ValidationError):
        SDSDocument.model_validate(payload)


def test_sds_section_forbids_extra_fields():
    with pytest.raises(ValidationError):
        SDSSection.model_validate(
            {
                "section_number": 4,
                "section_title_ja": "応急措置",
                "content_markdown": "",
                "extra": "nope",
            }
        )


def test_json_schema_generation_is_stable_and_strict():
    schema = SDSDocument.model_json_schema()

    # Should be JSON-serializable and match the module-level cached constant.
    json.dumps(schema)
    assert schema == SDS_JSON_SCHEMA

    assert schema.get("additionalProperties") is False

    defs = schema.get("$defs", {})
    assert defs, "expected nested models to appear under $defs"
    for name, definition in defs.items():
        if definition.get("type") == "object":
            assert definition.get("additionalProperties") is False, name
