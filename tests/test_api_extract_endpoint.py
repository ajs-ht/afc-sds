import base64
import io
import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import anthropic
import httpx
import pytest
from pypdf import PdfReader, PdfWriter

from app.main import app
from app.services.claude_client import get_claude_client
from tests.factories import FakeStreamContext, fake_message, minimal_sds_payload


def _fake_response(status_code: int) -> httpx.Response:
    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    return httpx.Response(status_code=status_code, request=request)


def _override_client_raising(exc: Exception):
    fake_client = MagicMock()
    fake_client.messages.stream.side_effect = exc
    app.dependency_overrides[get_claude_client] = lambda: fake_client
    return fake_client


@pytest.fixture(autouse=True)
def _clear_overrides():
    yield
    app.dependency_overrides.pop(get_claude_client, None)


def _override_client_with_message(message):
    fake_client = MagicMock()
    fake_client.messages.stream.return_value = FakeStreamContext(message)
    app.dependency_overrides[get_claude_client] = lambda: fake_client
    return fake_client


def test_extract_happy_path_returns_structured_json(client, auth_headers, sample_pdf_bytes):
    payload = minimal_sds_payload()
    payload["section_1_product_and_company"] = {"product_name": "テスト洗浄剤"}
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    _override_client_with_message(message)

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["data"]["section_1_product_and_company"]["product_name"] == "テスト洗浄剤"
    assert body["warnings"] == []
    assert body["usage"]["input_tokens"] == 1000


def test_extract_unsupported_file_type_returns_400(client, auth_headers):
    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("notes.txt", b"hello", "text/plain")},
    )
    assert response.status_code == 400
    assert response.json()["error"]["type"] == "unsupported_file_type"


def test_extract_empty_file_returns_400(client, auth_headers):
    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("empty.pdf", b"", "application/pdf")},
    )
    assert response.status_code == 400
    assert response.json()["error"]["type"] == "empty_file"


def test_extract_oversized_file_returns_400(client, auth_headers, monkeypatch):
    from app import config

    config.get_settings.cache_clear()
    monkeypatch.setenv("MAX_UPLOAD_MB", "1")

    oversized = b"%PDF-1.4\n" + b"0" * (2 * 1024 * 1024)
    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("big.pdf", oversized, "application/pdf")},
    )
    config.get_settings.cache_clear()
    monkeypatch.delenv("MAX_UPLOAD_MB", raising=False)

    assert response.status_code == 400
    assert response.json()["error"]["type"] == "file_too_large"


def test_extract_refusal_returns_422(client, auth_headers, sample_pdf_bytes):
    message = fake_message(
        text="",
        stop_reason="refusal",
        stop_details=SimpleNamespace(category="cyber"),
    )
    _override_client_with_message(message)

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 422
    assert response.json()["error"]["type"] == "extraction_refused"


def test_extract_truncated_output_returns_502(client, auth_headers, sample_pdf_bytes):
    message = fake_message(text='{"incomplete": tr', stop_reason="max_tokens")
    _override_client_with_message(message)

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 502
    assert response.json()["error"]["type"] == "extraction_truncated"


def _pdf_with_pages(count: int) -> bytes:
    writer = PdfWriter()
    for _ in range(count):
        writer.add_blank_page(width=595, height=842)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def test_extract_with_pages_sends_only_selected_pages(client, auth_headers):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="end_turn")
    fake_client = _override_client_with_message(message)

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("multi_sds.pdf", _pdf_with_pages(11), "application/pdf")},
        data={"pages": "6-11"},
    )

    assert response.status_code == 200
    _, kwargs = fake_client.messages.stream.call_args
    document_block = kwargs["messages"][0]["content"][0]
    sent_pdf = base64.b64decode(document_block["source"]["data"])
    assert len(PdfReader(io.BytesIO(sent_pdf)).pages) == 6


def test_extract_with_invalid_pages_returns_400(client, auth_headers):
    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("multi_sds.pdf", _pdf_with_pages(3), "application/pdf")},
        data={"pages": "2-9"},
    )

    assert response.status_code == 400
    assert response.json()["error"]["type"] == "invalid_page_range"


def test_extract_with_pages_on_image_returns_400(client, auth_headers):
    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("scan.png", b"\x89PNG\r\n\x1a\n" + b"0" * 100, "image/png")},
        data={"pages": "1"},
    )

    assert response.status_code == 400
    assert response.json()["error"]["type"] == "invalid_page_range"


def test_extract_max_tokens_with_valid_json_returns_warning(
    client, auth_headers, sample_pdf_bytes
):
    payload = minimal_sds_payload()
    message = fake_message(text=json.dumps(payload), stop_reason="max_tokens")
    _override_client_with_message(message)

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 200
    assert response.json()["warnings"] == ["output_truncated_max_tokens"]


def test_extract_too_many_pages_returns_400(client, auth_headers, monkeypatch):
    from app import config

    config.get_settings.cache_clear()
    monkeypatch.setenv("MAX_PDF_PAGES", "2")

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("multi.pdf", _pdf_with_pages(3), "application/pdf")},
    )
    config.get_settings.cache_clear()
    monkeypatch.delenv("MAX_PDF_PAGES", raising=False)

    assert response.status_code == 400
    assert response.json()["error"]["type"] == "too_many_pages"


def test_extract_oversized_pdf_with_pages_returns_400_before_slicing(
    client, auth_headers, monkeypatch
):
    """Regression test: an oversized upload combined with `pages` must be
    rejected by validate_upload() before slice_pdf_pages() ever parses it."""
    from app import config

    config.get_settings.cache_clear()
    monkeypatch.setenv("MAX_UPLOAD_MB", "1")

    oversized = _pdf_with_pages(3) + b"0" * (2 * 1024 * 1024)
    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("big.pdf", oversized, "application/pdf")},
        data={"pages": "1"},
    )
    config.get_settings.cache_clear()
    monkeypatch.delenv("MAX_UPLOAD_MB", raising=False)

    assert response.status_code == 400
    assert response.json()["error"]["type"] == "file_too_large"


def test_extract_upstream_error_returns_503(client, auth_headers, sample_pdf_bytes):
    _override_client_raising(
        anthropic.RateLimitError("rate limited", response=_fake_response(429), body=None)
    )

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 503
    body = response.json()
    assert body["error"]["type"] == "upstream_error"
    assert body["error"]["request_id"] == response.headers["X-Request-ID"]


def test_extract_invalid_document_returns_400(client, auth_headers, sample_pdf_bytes):
    _override_client_raising(
        anthropic.BadRequestError("bad request", response=_fake_response(400), body=None)
    )

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 400
    assert response.json()["error"]["type"] == "invalid_document"


def test_extract_invalid_response_returns_502(client, auth_headers, sample_pdf_bytes):
    message = fake_message(text="not json at all", stop_reason="end_turn")
    fake_client = MagicMock()
    fake_client.messages.stream.return_value = FakeStreamContext(message)
    app.dependency_overrides[get_claude_client] = lambda: fake_client

    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 502
    assert response.json()["error"]["type"] == "extraction_invalid_response"


def test_extract_unexpected_error_returns_500_internal_error(auth_headers, sample_pdf_bytes):
    """A bare, unmapped exception must still hit the catch-all handler with
    the documented {"error": {...}} shape and an X-Request-ID header.

    Uses raise_server_exceptions=False: Starlette's ServerErrorMiddleware
    always re-raises after sending the handler's response, purely so a test
    client can surface bugs during development — that re-raise isn't
    something an HTTP client ever sees, so it must be disabled here to
    inspect the response our handler actually produced.
    """
    from fastapi.testclient import TestClient

    _override_client_raising(RuntimeError("boom"))
    no_raise_client = TestClient(app, raise_server_exceptions=False)

    response = no_raise_client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )

    assert response.status_code == 500
    body = response.json()
    assert body["error"]["type"] == "internal_error"
    assert body["error"]["request_id"] == response.headers["X-Request-ID"]


def test_extract_missing_file_returns_validation_error(client, auth_headers):
    response = client.post("/v1/sds/extract", headers=auth_headers)

    assert response.status_code == 422
    body = response.json()
    assert body["error"]["type"] == "validation_error"
    assert body["error"]["request_id"] == response.headers["X-Request-ID"]


def test_extract_spoofed_content_type_rejected(client, auth_headers):
    response = client.post(
        "/v1/sds/extract",
        headers=auth_headers,
        files={"file": ("fake.pdf", b"not actually a pdf", "application/pdf")},
    )

    assert response.status_code == 400
    assert response.json()["error"]["type"] == "unsupported_file_type"
