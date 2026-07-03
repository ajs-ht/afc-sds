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
from app.services.extraction_service import build_content_block, extract_sds
from tests.factories import FakeStreamContext, fake_message, minimal_sds_payload



@pytest.fixture
def settings() -> Settings:
    return Settings(anthropic_api_key="k", api_key="s", model_id="claude-opus-4-8")


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


def test_extract_sds_success_builds_expected_request(settings):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    client = _client_returning(message)

    result = extract_sds(
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
    assert kwargs["system"][0]["cache_control"] == {"type": "ephemeral"}
    # No output_config: the full schema exceeds Claude's compiled-grammar size
    # limit, so the schema is embedded in the (cached) system prompt instead
    # and enforced by post-hoc Pydantic validation below.
    assert "output_config" not in kwargs
    assert "section_1_product_and_company" in kwargs["system"][0]["text"]
    content_blocks = kwargs["messages"][0]["content"]
    assert content_blocks[0]["type"] == "document"


def test_extract_sds_strips_wrapping_code_fence(settings):
    payload = minimal_sds_payload()
    fenced_text = "```json\n" + json.dumps(payload) + "\n```"
    message = fake_message(text=fenced_text, stop_reason="end_turn")
    client = _client_returning(message)

    result = extract_sds(
        content=b"%PDF-1.4...",
        content_type="application/pdf",
        client=client,
        settings=settings,
        request_id="req-3",
    )

    assert result.data.schema_version == "1.0"


def test_extract_sds_uses_image_block_for_image_upload(settings):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    client = _client_returning(message)

    extract_sds(
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


def test_refusal_raises_claude_refusal_error(settings):
    message = fake_message(
        text="",
        stop_reason="refusal",
        stop_details=SimpleNamespace(category="cyber"),
    )
    client = _client_returning(message)

    with pytest.raises(ClaudeRefusalError) as excinfo:
        extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-3",
        )
    assert excinfo.value.details["category"] == "cyber"


def test_max_tokens_with_invalid_json_raises_truncated_error(settings):
    message = fake_message(text='{"incomplete": tr', stop_reason="max_tokens")
    client = _client_returning(message)

    with pytest.raises(ClaudeTruncatedError):
        extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-4",
        )


def test_max_tokens_with_valid_json_returns_warning(settings):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="max_tokens")
    client = _client_returning(message)

    result = extract_sds(
        content=b"data",
        content_type="application/pdf",
        client=client,
        settings=settings,
        request_id="req-5",
    )

    assert result.warnings == ["output_truncated_max_tokens"]


def test_invalid_json_with_other_stop_reason_raises_invalid_response(settings):
    message = fake_message(text="not json at all", stop_reason="end_turn")
    client = _client_returning(message)

    with pytest.raises(ClaudeResponseInvalidError):
        extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-6",
        )


# --- Anthropic SDK exception mapping ---------------------------------------


def _fake_response(status_code: int) -> httpx.Response:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    return httpx.Response(status_code=status_code, request=request)


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
def test_anthropic_status_errors_map_to_upstream_error(settings, exc, expected_status):
    client = _client_raising(exc)

    with pytest.raises(ClaudeUpstreamError) as excinfo:
        extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-7",
        )
    assert excinfo.value.status_code == expected_status


def test_anthropic_connection_error_maps_to_503(settings):
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    exc = anthropic.APIConnectionError(message="connection failed", request=request)
    client = _client_raising(exc)

    with pytest.raises(ClaudeUpstreamError) as excinfo:
        extract_sds(
            content=b"data",
            content_type="application/pdf",
            client=client,
            settings=settings,
            request_id="req-8",
        )
    assert excinfo.value.status_code == 503
