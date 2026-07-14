import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.schemas.sds import SCHEMA_VERSION, SDS_JSON_SCHEMA, SDSDocument, SDSSection
from tests.factories import minimal_sds_payload

SCHEMA_SNAPSHOT_PATH = Path(__file__).parent / "fixtures" / "schema_snapshot.json"

# Regenerates tests/fixtures/schema_snapshot.json from the current schema.
REGENERATE_SNAPSHOT_CMD = (
    "python -c \"import json, pathlib; "
    "from app.schemas.sds import SDS_JSON_SCHEMA, SCHEMA_VERSION; "
    "pathlib.Path('tests/fixtures/schema_snapshot.json').write_text("
    "json.dumps({'schema_version': SCHEMA_VERSION, 'schema': SDS_JSON_SCHEMA}, "
    "ensure_ascii=False, indent=2, sort_keys=True) + '\\n', encoding='utf-8')\""
)


def test_sds_document_instantiates_with_minimal_data():
    doc = SDSDocument.model_validate(minimal_sds_payload())

    assert doc.schema_version == "2.1"
    assert doc.section_4_first_aid.section_title_ja == "応急措置"
    assert doc.additional_documents == []
    assert doc.extraction_notes is None


def test_additional_documents_accepts_detected_entries():
    payload = minimal_sds_payload()
    payload["additional_documents"] = [
        {"product_name": "スーパーMD シール B剤", "start_page": 6, "end_page": 11}
    ]

    doc = SDSDocument.model_validate(payload)

    assert doc.additional_documents[0].product_name == "スーパーMD シール B剤"
    assert doc.additional_documents[0].start_page == 6
    assert doc.additional_documents[0].end_page == 11


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
    payload["section_8_exposure_controls"] = {
        "section_number": 8,
        "section_title_ja": "ばく露防止及び保護措置",
        "exposure_limits": [
            {
                "substance_name": "エタノール",
                "limit_type": "日本産業衛生学会 許容濃度",
                "value": "1000 ppm",
            }
        ],
        "engineering_controls": "局所排気装置を設置すること",
        "protective_equipment": {"respiratory": "有機ガス用防毒マスク", "hands": "耐溶剤性保護手袋"},
        "content_markdown": "## ばく露防止...",
    }
    payload["section_9_physical_chemical_properties"] = {
        "section_number": 9,
        "section_title_ja": "物理的及び化学的性質",
        "physical_state": "液体",
        "flash_point": "13℃(密閉式)",
        "explosion_range_lower": "3.3 vol%",
        "explosion_range_upper": "19 vol%",
        "density": "0.79 g/cm3 (20℃)",
        "content_markdown": "## 物理的及び化学的性質...",
    }
    payload["section_14_transport"] = {
        "section_number": 14,
        "section_title_ja": "輸送上の注意",
        "un_number": "1170",
        "un_proper_shipping_name": "エタノール溶液",
        "transport_hazard_class": "3",
        "packing_group": "II",
        "content_markdown": "## 輸送上の注意...",
    }
    payload["section_15_regulatory"] = {
        "section_number": 15,
        "section_title_ja": "適用法令",
        "regulations": [
            {"law_name": "消防法", "classification": "第4類引火性液体 アルコール類"},
            {"law_name": "労働安全衛生法", "classification": "危険物・引火性の物"},
        ],
        "content_markdown": "## 適用法令...",
    }
    payload["extraction_notes"] = "セクション13は判読不能のため空欄"

    doc = SDSDocument.model_validate(payload)

    assert doc.section_1_product_and_company.manufacturer.company_name == "テスト株式会社"
    assert doc.section_2_hazards_identification.pictograms == ["GHS06"]
    assert doc.section_3_composition.ingredients[0].concentration == "10~20%"
    assert doc.section_8_exposure_controls.exposure_limits[0].value == "1000 ppm"
    assert doc.section_9_physical_chemical_properties.flash_point == "13℃(密閉式)"
    assert doc.section_14_transport.un_number == "1170"
    assert doc.section_15_regulatory.regulations[0].law_name == "消防法"


def test_structured_sections_accept_minimal_section_shape():
    """The generic {number, title, markdown} shape stays valid for the
    structured sections — every added field is optional, so a sparse SDS
    (or a model that found nothing to structure) still validates."""
    doc = SDSDocument.model_validate(minimal_sds_payload())

    assert doc.section_8_exposure_controls.exposure_limits == []
    assert doc.section_9_physical_chemical_properties.flash_point is None
    assert doc.section_14_transport.un_number is None
    assert doc.section_15_regulatory.regulations == []


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


def test_schema_change_requires_version_bump():
    """SDSDocument is a versioned contract (served at GET /v1/sds/schema;
    downstream systems generate types from it). Any change to the output
    shape must bump SCHEMA_VERSION in app/schemas/sds.py, update the README,
    and regenerate the committed snapshot with:

        %s
    """ % REGENERATE_SNAPSHOT_CMD

    snapshot = json.loads(SCHEMA_SNAPSHOT_PATH.read_text(encoding="utf-8"))

    if snapshot["schema"] != SDS_JSON_SCHEMA and snapshot["schema_version"] == SCHEMA_VERSION:
        pytest.fail(
            "SDS_JSON_SCHEMA changed but SCHEMA_VERSION did not. The schema is "
            "a versioned contract: bump SCHEMA_VERSION in app/schemas/sds.py, "
            "update the README, then regenerate the snapshot:\n"
            f"  {REGENERATE_SNAPSHOT_CMD}"
        )
    if snapshot["schema"] != SDS_JSON_SCHEMA or snapshot["schema_version"] != SCHEMA_VERSION:
        pytest.fail(
            "tests/fixtures/schema_snapshot.json is out of date with the "
            "current schema/version. Regenerate it:\n"
            f"  {REGENERATE_SNAPSHOT_CMD}"
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
