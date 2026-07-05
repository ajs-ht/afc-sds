import pytest

from app.schemas.sds import SDSDocument
from app.services.postvalidation import collect_domain_warnings
from tests.factories import minimal_sds_payload


def _doc(**section_overrides) -> SDSDocument:
    payload = minimal_sds_payload()
    for key, value in section_overrides.items():
        payload[key] = {**payload[key], **value}
    return SDSDocument.model_validate(payload)


def test_minimal_document_produces_no_warnings():
    assert collect_domain_warnings(_doc()) == []


# --- CAS numbers ------------------------------------------------------------


@pytest.mark.parametrize(
    "cas",
    [
        "7732-18-5",  # water
        "64-17-5",  # ethanol
        "108-88-3",  # toluene
    ],
)
def test_valid_cas_numbers_pass(cas):
    doc = _doc(
        section_3_composition={"ingredients": [{"substance_name": "x", "cas_number": cas}]}
    )
    assert collect_domain_warnings(doc) == []


@pytest.mark.parametrize(
    "cas",
    [
        "7732-18-4",  # wrong check digit
        "64-17",  # missing check digit segment
        "not-a-cas",
        "1234567890",
    ],
)
def test_invalid_cas_numbers_warn(cas):
    doc = _doc(
        section_3_composition={"ingredients": [{"substance_name": "x", "cas_number": cas}]}
    )
    assert collect_domain_warnings(doc) == [f"invalid_cas_number:{cas}"]


def test_null_cas_number_is_not_checked():
    doc = _doc(
        section_3_composition={"ingredients": [{"substance_name": "混合物"}]}
    )
    assert collect_domain_warnings(doc) == []


# --- pictograms ---------------------------------------------------------------


def test_known_pictograms_pass():
    doc = _doc(
        section_2_hazards_identification={"pictograms": ["GHS02", "GHS07", "GHS09"]}
    )
    assert collect_domain_warnings(doc) == []


def test_unknown_pictogram_warns():
    doc = _doc(section_2_hazards_identification={"pictograms": ["GHS10", "炎"]})
    assert collect_domain_warnings(doc) == [
        "unknown_pictogram:GHS10",
        "unknown_pictogram:炎",
    ]


# --- H/P codes ---------------------------------------------------------------


@pytest.mark.parametrize(
    "statement",
    [
        "H225 引火性の高い液体及び蒸気",
        "H360FD 生殖能又は胎児への悪影響のおそれ",
        "P301+P310 飲み込んだ場合:直ちに医師に連絡すること。",
        "引火性の高い液体及び蒸気",  # no leading code — never judged
        "",
    ],
)
def test_wellformed_or_codeless_statements_pass(statement):
    doc = _doc(section_2_hazards_identification={"hazard_statements": [statement]})
    assert collect_domain_warnings(doc) == []


@pytest.mark.parametrize(
    ("statement", "token"),
    [
        ("H22 引火性の高い液体", "H22"),
        ("H2250 引火性の高い液体", "H2250"),
        ("P30+P310 飲み込んだ場合", "P30+P310"),
    ],
)
def test_malformed_leading_codes_warn(statement, token):
    doc = _doc(section_2_hazards_identification={"precautionary_statements": [statement]})
    assert collect_domain_warnings(doc) == [f"invalid_ghs_code:{token}"]


# --- UN numbers ----------------------------------------------------------------


@pytest.mark.parametrize("un", ["1230", "UN1230", "UN 1230"])
def test_valid_un_numbers_pass(un):
    doc = _doc(section_14_transport={"un_number": un})
    assert collect_domain_warnings(doc) == []


@pytest.mark.parametrize("un", ["123", "12345", "UNX123"])
def test_invalid_un_numbers_warn(un):
    doc = _doc(section_14_transport={"un_number": un})
    assert collect_domain_warnings(doc) == [f"invalid_un_number:{un}"]


def test_null_un_number_is_not_checked():
    assert collect_domain_warnings(_doc(section_14_transport={"un_number": None})) == []


# --- explicit-absence notations (許容リスト) -----------------------------------


@pytest.mark.parametrize(
    "value",
    ["非開示", "非該当", "不明", "企業秘密", "データなし", "記載なし", "なし", "―", "-", "－", "／"],
)
def test_explicit_absence_cas_values_are_not_flagged(value):
    doc = _doc(
        section_3_composition={"ingredients": [{"substance_name": "x", "cas_number": value}]}
    )
    assert collect_domain_warnings(doc) == []


@pytest.mark.parametrize(
    "value",
    ["非該当", "分類基準に該当しない", "該当なし", "適用外", "対象外", "非該当（国連分類）", "―"],
)
def test_explicit_absence_un_values_are_not_flagged(value):
    doc = _doc(section_14_transport={"un_number": value})
    assert collect_domain_warnings(doc) == []


def test_absence_marker_does_not_mask_a_real_invalid_value():
    # Values that merely *fail* the format without an absence marker still warn.
    doc = _doc(section_14_transport={"un_number": "UN12345"})
    assert collect_domain_warnings(doc) == ["invalid_un_number:UN12345"]


# --- aggregation ----------------------------------------------------------------


def test_multiple_violations_all_reported():
    doc = _doc(
        section_2_hazards_identification={"pictograms": ["GHS99"]},
        section_3_composition={"ingredients": [{"cas_number": "1-11-1"}]},
        section_14_transport={"un_number": "UN12"},
    )
    warnings = collect_domain_warnings(doc)
    assert "invalid_cas_number:1-11-1" in warnings
    assert "unknown_pictogram:GHS99" in warnings
    assert "invalid_un_number:UN12" in warnings
