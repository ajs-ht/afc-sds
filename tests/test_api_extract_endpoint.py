import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from app.main import app
from app.services.claude_client import get_claude_client
from tests.factories import FakeStreamContext, fake_message, minimal_sds_payload


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
