import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import anthropic
import httpx
import pytest

from app.config import Settings
from app.core.exceptions import (
    ClaudeRefusalError,
    ClaudeResponseInvalidError,
    ClaudeTruncatedError,
    ClaudeUpstreamError,
)
from app.schemas.sds import SDS_JSON_SCHEMA
from app.services import extraction_service
from app.services.extraction_service import build_content_block, extract_sds
from tests.factories import FakeStreamContext, fake_message, minimal_sds_payload



@pytest.fixture
def settings() -> Settings:
    return Settings(anthropic_api_key="k", api_key="s", model_id="claude-opus-4-8")


@pytest.fixture
def so_settings() -> Settings:
    """Settings with structured outputs opted in (off by default)."""
    return Settings(
        anthropic_api_key="k",
        api_key="s",
        model_id="claude-opus-4-8",
        use_structured_outputs=True,
    )


def _client_returning(message):
    client = MagicMock()
    client.messages.stream.return_value = FakeStreamContext(message)
    return client


def _client_raising(exc: Exception):
    client = MagicMock()
    client.messages.stream.side_effect = exc
    return client


# --- build_content_block -----------------------------------------------


def test_build_content_block_pdf():
    block = build_content_block(b"%PDF-1.4...", "application/pdf")
    assert block["type"] == "document"
    assert block["source"]["media_type"] == "application/pdf"
    assert block["source"]["type"] == "base64"


def test_build_content_block_image():
    block = build_content_block(b"\x89PNG...", "image/png")
    assert block["type"] == "image"
    assert block["source"]["media_type"] == "image/png"


# --- happy path -----------------------------------------------------------


async def test_extract_sds_success_builds_expected_request(settings):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    client = _client_returning(message)

    result = await extract_sds(
        content=b"%PDF-1.4...",
        content_type="application/pdf",
        client=client,
        settings=settings,
        request_id="req-1",
    )

    assert result.warnings == []
    assert result.model == "claude-opus-4-8"
    assert result.usage.input_tokens == 1000
    assert result.usage.cache_creation_input_tokens == 200

    _, kwargs = client.messages.stream.call_args
    assert kwargs["model"] == settings.model_id
    assert kwargs["max_tokens"] == settings.max_output_tokens
    # Sampling params are removed on Opus 4.7+ — sending temperature is a 400.
    assert "temperature" not in kwargs
    assert kwargs["system"][0]["cache_control"] == {"type": "ephemeral"}
    # Default mode: structured outputs are off (the SDS schema exceeds the
    # API's compiled-grammar limits — see prompts.py), so the schema is
    # embedded in the (cached) system prompt and enforced by post-hoc
    # Pydantic validation.
    assert "output_config" not in kwargs
    assert "section_1_product_and_company" in kwargs["system"][0]["text"]
    content_blocks = kwargs["messages"][0]["content"]
    assert content_blocks[0]["type"] == "document"


async def test_extract_sds_uses_structured_outputs_when_enabled(so_settings):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    client = _client_returning(message)

    result = await extract_sds(
        content=b"%PDF-1.4...",
        content_type="application/pdf",
        client=client,
        settings=so_settings,
        request_id="req-1b",
    )

    assert result.warnings == []
    _, kwargs = client.messages.stream.call_args
    # Structured outputs enforce the schema at the API level, so the schema
    # JSON must not also be embedded in the system prompt.
    assert kwargs["output_config"]["format"]["type"] == "json_schema"
    assert kwargs["output_config"]["format"]["schema"] == SDS_JSON_SCHEMA
    assert "section_1_product_and_company" not in kwargs["system"][0]["text"]


async def test_extract_sds_strips_wrapping_code_fence(settings):
    payload = minimal_sds_payload()
    fenced_text = "```json\n" + json.dumps(payload) + "\n```"
    message = fake_message(text=fenced_text, stop_reason="end_turn")
    client = _client_returning(message)

    result = await extract_sds(
        content=b"%PDF-1.4...",
        content_type="application/pdf",
        client=client,
        settings=settings,
        request_id="req-3",
    )

    assert result.data.schema_version == "2.1"


async def test_extract_sds_uses_image_block_for_image_upload(settings):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    client = _client_returning(message)

    await extract_sds(
        content=b"\x89PNG...",
        content_type="image/png",
        client=client,
        settings=settings,
        request_id="req-2",
    )

    _, kwargs = client.messages.stream.call_args
    content_blocks = kwargs["messages"][0]["content"]
    assert content_blocks[0]["type"] == "image"


# --- stop_reason branches --------------------------------------------------


async def test_refusal_raises_claude_refusal_error(settings):
    message = fake_message(
        text="",
        stop_reason="refusal",
        stop_details=SimpleNamespace(category="cyber"),
    )
    client = _client_returning(message)

    with pytest.raises(ClaudeRefusalError) as excinfo:
        await extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-3",
        )
    assert excinfo.value.details["category"] == "cyber"


async def test_max_tokens_with_invalid_json_raises_truncated_error(settings):
    message = fake_message(text='{"incomplete": tr', stop_reason="max_tokens")
    client = _client_returning(message)

    with pytest.raises(ClaudeTruncatedError):
        await extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-4",
        )


async def test_max_tokens_with_valid_json_returns_warning(settings):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="max_tokens")
    client = _client_returning(message)

    result = await extract_sds(
        content=b"data",
        content_type="application/pdf",
        client=client,
        settings=settings,
        request_id="req-5",
    )

    assert result.warnings == ["output_truncated_max_tokens"]


async def test_invalid_json_is_retried_once_then_raises(settings):
    message = fake_message(text="not json at all", stop_reason="end_turn")
    client = _client_returning(message)

    with pytest.raises(ClaudeResponseInvalidError):
        await extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-6",
        )

    # One automatic retry before giving up with extraction_invalid_response.
    assert client.messages.stream.call_count == 2


# --- invalid-response retry --------------------------------------------------


async def test_invalid_json_retry_success_adds_warning_and_sums_usage(settings):
    payload = minimal_sds_payload()
    client = MagicMock()
    client.messages.stream.side_effect = [
        FakeStreamContext(fake_message(text="not json at all", stop_reason="end_turn")),
        FakeStreamContext(fake_message(text=json.dumps(payload), stop_reason="end_turn")),
    ]

    result = await extract_sds(
        content=b"data",
        content_type="application/pdf",
        client=client,
        settings=settings,
        request_id="req-6b",
    )

    assert client.messages.stream.call_count == 2
    assert result.warnings == ["retried_invalid_response"]
    # Usage must cover both API calls, not just the successful one.
    assert result.usage.input_tokens == 2000
    assert result.usage.output_tokens == 1000
    assert result.usage.cache_creation_input_tokens == 400


async def test_truncated_response_is_not_retried(settings):
    message = fake_message(text='{"incomplete": tr', stop_reason="max_tokens")
    client = _client_returning(message)

    with pytest.raises(ClaudeTruncatedError):
        await extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-6c",
        )

    # Retrying a max_tokens truncation would just truncate again.
    assert client.messages.stream.call_count == 1


async def test_refusal_on_retry_raises_refusal_error(settings):
    client = MagicMock()
    client.messages.stream.side_effect = [
        FakeStreamContext(fake_message(text="not json at all", stop_reason="end_turn")),
        FakeStreamContext(
            fake_message(
                text="",
                stop_reason="refusal",
                stop_details=SimpleNamespace(category="cyber"),
            )
        ),
    ]

    with pytest.raises(ClaudeRefusalError):
        await extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-6d",
        )


# --- multi-SDS detection ------------------------------------------------------


async def test_additional_documents_add_detection_warning(settings):
    payload = minimal_sds_payload()
    payload["additional_documents"] = [
        {"product_name": "B剤", "start_page": 6, "end_page": 11}
    ]
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    client = _client_returning(message)

    result = await extract_sds(
        content=b"data",
        content_type="application/pdf",
        client=client,
        settings=settings,
        request_id="req-6f",
    )

    assert result.warnings == ["additional_sds_documents_detected"]
    assert result.data.additional_documents[0].start_page == 6


# --- domain post-validation warnings ----------------------------------------


async def test_domain_warnings_are_appended(settings):
    payload = minimal_sds_payload()
    payload["section_3_composition"]["ingredients"] = [
        {"substance_name": "トルエン", "cas_number": "108-88-4"}  # bad check digit
    ]
    payload["section_2_hazards_identification"]["pictograms"] = ["GHS02", "GHS99"]
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    client = _client_returning(message)

    result = await extract_sds(
        content=b"data",
        content_type="application/pdf",
        client=client,
        settings=settings,
        request_id="req-6e",
    )

    assert result.warnings == [
        "invalid_cas_number:108-88-4",
        "unknown_pictogram:GHS99",
    ]


# --- structured-outputs grammar-size fallback -------------------------------


def _fake_response(status_code: int) -> httpx.Response:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    return httpx.Response(status_code=status_code, request=request)


def _grammar_too_large_error() -> anthropic.BadRequestError:
    return anthropic.BadRequestError(
        "The compiled grammar is too large. Please reduce the number of strict tools",
        response=_fake_response(400),
        body=None,
    )


async def test_grammar_size_error_falls_back_to_embedded_schema(so_settings):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    client = MagicMock()
    client.messages.stream.side_effect = [
        _grammar_too_large_error(),
        FakeStreamContext(message),
    ]

    result = await extract_sds(
        content=b"%PDF-1.4...",
        content_type="application/pdf",
        client=client,
        settings=so_settings,
        request_id="req-9",
    )

    assert result.warnings == ["structured_outputs_unavailable"]
    assert client.messages.stream.call_count == 2
    _, retry_kwargs = client.messages.stream.call_args
    assert "output_config" not in retry_kwargs
    assert "section_1_product_and_company" in retry_kwargs["system"][0]["text"]
    assert extraction_service._grammar_too_large is True


async def test_grammar_size_error_is_remembered_across_requests(so_settings):
    payload = minimal_sds_payload()
    extraction_service._grammar_too_large = True

    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    client = _client_returning(message)

    result = await extract_sds(
        content=b"%PDF-1.4...",
        content_type="application/pdf",
        client=client,
        settings=so_settings,
        request_id="req-10",
    )

    # Goes straight to the fallback path: one call, no output_config, but the
    # response still notes that structured outputs were requested and unusable.
    assert result.warnings == ["structured_outputs_unavailable"]
    assert client.messages.stream.call_count == 1
    _, kwargs = client.messages.stream.call_args
    assert "output_config" not in kwargs


async def test_non_grammar_bad_request_does_not_fall_back(so_settings):
    client = _client_raising(
        anthropic.BadRequestError("bad request", response=_fake_response(400), body=None)
    )

    with pytest.raises(ClaudeUpstreamError):
        await extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=so_settings,
            request_id="req-11",
        )

    assert client.messages.stream.call_count == 1
    assert extraction_service._grammar_too_large is False


# --- Anthropic SDK exception mapping ---------------------------------------


@pytest.mark.parametrize(
    ("exc", "expected_status"),
    [
        (anthropic.RateLimitError("rate limited", response=_fake_response(429), body=None), 503),
        (anthropic.InternalServerError("server error", response=_fake_response(500), body=None), 503),
        (
            anthropic.AuthenticationError("bad key", response=_fake_response(401), body=None),
            500,
        ),
        (
            anthropic.PermissionDeniedError("forbidden", response=_fake_response(403), body=None),
            500,
        ),
        (anthropic.NotFoundError("bad model", response=_fake_response(404), body=None), 500),
        (
            anthropic.BadRequestError("bad request", response=_fake_response(400), body=None),
            500,
        ),
    ],
)
async def test_anthropic_status_errors_map_to_upstream_error(settings, exc, expected_status):
    client = _client_raising(exc)

    with pytest.raises(ClaudeUpstreamError) as excinfo:
        await extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-7",
        )
    assert excinfo.value.status_code == expected_status


async def test_anthropic_connection_error_maps_to_503(settings):
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    exc = anthropic.APIConnectionError(message="connection failed", request=request)
    client = _client_raising(exc)

    with pytest.raises(ClaudeUpstreamError) as excinfo:
        await extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-8",
        )
    assert excinfo.value.status_code == 503
