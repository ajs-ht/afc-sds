"""End-to-end test against the real Claude API.

Skipped by default (see pyproject.toml -m 'not integration'). To run it:

    RUN_INTEGRATION=1 ANTHROPIC_API_KEY=sk-ant-... pytest -m integration

Sends a small, self-authored, non-proprietary Japanese SDS-shaped PDF fixture
through the real /v1/sds/extract endpoint and does a light sanity check on
the result plus logs token usage for a cost sanity check.
"""

from pathlib import Path

import pytest

from tests.conftest import requires_live_api

pytestmark = pytest.mark.integration


@requires_live_api
def test_live_extraction_returns_structured_sds(client, auth_headers):
    fixture_path = Path(__file__).parent.parent / "fixtures" / "sample_sds.pdf"

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": (fixture_path.name, fixture_path.read_bytes(), "application/pdf")},
    )

    assert response.status_code == 200, response.text
    body = response.json()

    assert body["data"]["schema_version"] == "2.0"
    assert "section_1_product_and_company" in body["data"]

    # The decisive check for structured-outputs viability: if the compiled
    # grammar exceeded the API's size limit, the service silently fell back
    # to the prompt-embedded schema and flagged it here. Fail loudly so the
    # regression (schema grew too big again) is caught, not papered over.
    assert "structured_outputs_unavailable" not in body["warnings"], (
        "structured outputs grammar was rejected as too large; "
        "slim down SDS_JSON_SCHEMA or flip use_structured_outputs off"
    )

    usage = body["usage"]
    print(
        f"\n[integration] model={body['model']} "
        f"input_tokens={usage['input_tokens']} output_tokens={usage['output_tokens']} "
        f"cache_read_input_tokens={usage['cache_read_input_tokens']}"
    )
