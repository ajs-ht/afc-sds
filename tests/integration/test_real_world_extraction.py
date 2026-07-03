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
        f"-> {result_path}"
    )
