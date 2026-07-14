"""Guards for the prompt-caching invariants (see app/services/prompts.py).

Both system-prompt variants are sent with `cache_control: {"type":
"ephemeral"}`; prompt caching only works if the prompt bytes are identical
across requests, so any per-request variation (request_id, timestamps,
filenames, ...) silently multiplies API cost. These tests make that
invariant executable.
"""

import json
from unittest.mock import MagicMock

import pytest

from app.config import Settings
from app.schemas.sds import SDS_JSON_SCHEMA
from app.services.extraction_service import extract_sds
from app.services.prompts import (
    SYSTEM_PROMPT_BASE,
    SYSTEM_PROMPT_WITH_SCHEMA,
    USER_INSTRUCTION,
)
from tests.factories import FakeStreamContext, fake_message, minimal_sds_payload


def test_with_schema_prompt_is_base_plus_schema_appendix():
    assert SYSTEM_PROMPT_WITH_SCHEMA.startswith(SYSTEM_PROMPT_BASE)
    appendix = SYSTEM_PROMPT_WITH_SCHEMA[len(SYSTEM_PROMPT_BASE):]
    # The schema is embedded verbatim (non-ASCII preserved), so the fallback
    # path enforces exactly the same contract as structured outputs would.
    assert json.dumps(SDS_JSON_SCHEMA, ensure_ascii=False) in appendix


def test_prompts_have_no_unresolved_placeholders():
    for text in (SYSTEM_PROMPT_BASE, SYSTEM_PROMPT_WITH_SCHEMA, USER_INSTRUCTION):
        assert "{schema_json}" not in text


@pytest.mark.parametrize("use_structured_outputs", [False, True])
async def test_system_prompt_is_byte_stable_across_requests(use_structured_outputs):
    settings = Settings(
        anthropic_api_key="k",
        api_key="s",
        use_structured_outputs=use_structured_outputs,
    )
    payload = minimal_sds_payload()
    client = MagicMock()
    client.messages.stream.side_effect = [
        FakeStreamContext(fake_message(text=json.dumps(payload), stop_reason="end_turn")),
        FakeStreamContext(fake_message(text=json.dumps(payload), stop_reason="end_turn")),
    ]

    # Two requests with different request_ids and different documents: the
    # system block must not vary with either.
    for request_id, content in (("req-a", b"%PDF-1.4 first"), ("req-b", b"%PDF-1.4 second")):
        await extract_sds(
            content=content,
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id=request_id,
        )

    first_system = client.messages.stream.call_args_list[0].kwargs["system"]
    second_system = client.messages.stream.call_args_list[1].kwargs["system"]
    assert first_system == second_system
    for system in (first_system, second_system):
        assert system[0]["cache_control"] == {"type": "ephemeral"}
