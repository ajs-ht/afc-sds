"""End-to-end test against the real Claude API.

Skipped by default (see pyproject.toml -m 'not integration'). To run it:

    RUN_INTEGRATION=1 ANTHROPIC_API_KEY=sk-ant-... pytest -m integration

Sends a small, self-authored, non-proprietary Japanese SDS-shaped PDF fixture
through the real /v1/sds/extract endpoint and does a light sanity check on
the result plus logs token usage for a cost sanity check.
"""

import os
from pathlib import Path

import pytest

RUN_INTEGRATION = os.environ.get("RUN_INTEGRATION") == "1"
REAL_API_KEY = os.environ.get("ANTHROPIC_API_KEY", "")
HAS_REAL_KEY = bool(REAL_API_KEY) and REAL_API_KEY != "test-anthropic-key"

pytestmark = pytest.mark.integration


@pytest.mark.skipif(
    not (RUN_INTEGRATION and HAS_REAL_KEY),
    reason="set RUN_INTEGRATION=1 and a real ANTHROPIC_API_KEY to run this test",
)
def test_live_extraction_returns_structured_sds(client, auth_headers):
    fixture_path = Path(__file__).parent.parent / "fixtures" / "sample_sds.pdf"

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": (fixture_path.name, fixture_path.read_bytes(), "application/pdf")},
    )

    assert response.status_code == 200, response.text
    body = response.json()

    assert body["data"]["schema_version"] == "1.0"
    assert "section_1_product_and_company" in body["data"]

    usage = body["usage"]
    print(
        f"\n[integration] model={body['model']} "
        f"input_tokens={usage['input_tokens']} output_tokens={usage['output_tokens']} "
        f"cache_read_input_tokens={usage['cache_read_input_tokens']}"
    )
