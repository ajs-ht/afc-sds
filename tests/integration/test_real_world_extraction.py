"""End-to-end test against the real Claude API using real-world SDS samples.

Skipped by default (see pyproject.toml -m 'not integration'), and skipped
cleanly if no samples are present. To run it:

    RUN_INTEGRATION=1 ANTHROPIC_API_KEY=sk-ant-... pytest -m integration \
        tests/integration/test_real_world_extraction.py -s

Drop real SDS files into tests/fixtures/real_world/ first (see the README
there) — they are gitignored and never committed. Each file is sent through
the real /v1/sds/extract endpoint, the full response is validated against
the SDSDocument schema, and the resulting JSON is written to
tests/fixtures/real_world/_results/<name>.json for manual review.

Accuracy scoring (optional): place a ground-truth file next to a sample as
<name>.expected.json — same shape as the extraction `data` (a partial
document is fine; only the structured fields you filled in are scored).
Faithful-text fields (content_markdown / raw_text) are excluded because
their formatting legitimately varies between runs. The field-level match
report is printed and written to _results/<name>.accuracy.json, so prompt
or model changes can be compared quantitatively across runs. Scoring is
report-only and never fails the test.
"""

import json
from pathlib import Path

import pytest

from app.schemas.sds import SDSDocument
from tests.conftest import requires_live_api

pytestmark = pytest.mark.integration

REAL_WORLD_DIR = Path(__file__).parent.parent / "fixtures" / "real_world"
RESULTS_DIR = REAL_WORLD_DIR / "_results"

MIME_TYPES = {
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
}

SAMPLE_PATHS = sorted(
    p for p in REAL_WORLD_DIR.glob("*") if p.suffix.lower() in MIME_TYPES
)

# Free-text fields whose formatting varies run-to-run; only structured
# projections are scored against the expected.json ground truth.
UNSCORED_FIELDS = {
    "content_markdown",
    "raw_text",
    "section_number",
    "section_title_ja",
    "schema_version",
    "extraction_notes",
}


def _flatten(value, prefix: str = "") -> dict:
    """Flatten nested dicts/lists to {"section_3_composition.ingredients[0].cas_number": ...}."""
    if isinstance(value, dict):
        flat: dict = {}
        for key, child in value.items():
            if key in UNSCORED_FIELDS:
                continue
            flat.update(_flatten(child, f"{prefix}.{key}" if prefix else key))
        return flat
    if isinstance(value, list):
        flat = {}
        for index, item in enumerate(value):
            flat.update(_flatten(item, f"{prefix}[{index}]"))
        return flat
    return {prefix: value}


def _normalize(value):
    return value.strip() if isinstance(value, str) else value


def _score_against_expected(expected: dict, actual: dict) -> dict:
    expected_flat = _flatten(expected)
    actual_flat = _flatten(actual)

    mismatches = []
    matched = 0
    for field, expected_value in sorted(expected_flat.items()):
        actual_value = actual_flat.get(field)
        if _normalize(expected_value) == _normalize(actual_value):
            matched += 1
        else:
            mismatches.append(
                {"field": field, "expected": expected_value, "actual": actual_value}
            )

    total = len(expected_flat)
    return {
        "total_fields": total,
        "matched": matched,
        "accuracy": round(matched / total, 4) if total else None,
        "mismatches": mismatches,
    }


@requires_live_api
@pytest.mark.skipif(
    not SAMPLE_PATHS,
    reason="no real-world samples in tests/fixtures/real_world/ — see its README",
)
@pytest.mark.parametrize("sample_path", SAMPLE_PATHS, ids=lambda p: p.name)
def test_real_world_sample_extraction(client, auth_headers, sample_path):
    mime_type = MIME_TYPES[sample_path.suffix.lower()]

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": (sample_path.name, sample_path.read_bytes(), mime_type)},
    )

    assert response.status_code == 200, response.text
    body = response.json()

    # Full structural validation, not just a spot check — every one of the
    # 16 sections must be present and correctly typed.
    SDSDocument.model_validate(body["data"])

    RESULTS_DIR.mkdir(exist_ok=True)
    result_path = RESULTS_DIR / f"{sample_path.stem}.json"
    result_path.write_text(
        json.dumps(body, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    usage = body["usage"]
    print(
        f"\n[real-world] file={sample_path.name} model={body['model']} "
        f"input_tokens={usage['input_tokens']} output_tokens={usage['output_tokens']} "
        f"cache_read_input_tokens={usage['cache_read_input_tokens']} "
        f"warnings={body['warnings']} "
        f"-> {result_path}"
    )

    expected_path = sample_path.parent / f"{sample_path.stem}.expected.json"
    if not expected_path.exists():
        return

    report = _score_against_expected(
        json.loads(expected_path.read_text(encoding="utf-8")), body["data"]
    )
    report["file"] = sample_path.name
    accuracy_path = RESULTS_DIR / f"{sample_path.stem}.accuracy.json"
    accuracy_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(
        f"[accuracy] file={sample_path.name} "
        f"matched={report['matched']}/{report['total_fields']} "
        f"accuracy={report['accuracy']} -> {accuracy_path}"
    )
    for mismatch in report["mismatches"][:20]:
        print(
            f"  [mismatch] {mismatch['field']}: "
            f"expected={mismatch['expected']!r} actual={mismatch['actual']!r}"
        )
