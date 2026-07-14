"""Tests for the request-context middleware (X-Request-ID + access log)
and the JSON log formatter."""

import json
import logging
import sys
import uuid

from app.core.logging import JsonFormatter, configure_logging


def test_every_response_carries_x_request_id(client):
    response = client.get("/healthz")

    assert response.status_code == 200
    request_id = response.headers["X-Request-ID"]
    # Must be a well-formed UUID so clients can rely on the format.
    uuid.UUID(request_id)


def test_error_body_request_id_matches_header(client):
    response = client.post("/v1/sds/extract", headers={"X-API-Key": "wrong"})

    assert response.status_code == 401
    body = response.json()
    assert body["error"]["request_id"] == response.headers["X-Request-ID"]


def test_access_log_records_request(client, caplog):
    with caplog.at_level(logging.INFO, logger="afc_sds.access"):
        client.get("/healthz")

    records = [r for r in caplog.records if r.name == "afc_sds.access"]
    assert len(records) == 1
    message = records[0].getMessage()
    assert "method=GET" in message
    assert "path=/healthz" in message
    assert "status=200" in message
    assert "duration_ms=" in message


def test_json_formatter_emits_valid_json():
    record = logging.LogRecord(
        name="afc_sds.access",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="request request_id=%s status=%d",
        args=("abc", 200),
        exc_info=None,
    )

    entry = json.loads(JsonFormatter().format(record))

    assert entry["level"] == "INFO"
    assert entry["logger"] == "afc_sds.access"
    assert entry["message"] == "request request_id=abc status=200"
    assert "time" in entry


def test_json_formatter_includes_exc_info():
    try:
        raise ValueError("boom")
    except ValueError:
        record = logging.LogRecord(
            name="afc_sds",
            level=logging.ERROR,
            pathname=__file__,
            lineno=1,
            msg="internal_error",
            args=(),
            exc_info=sys.exc_info(),
        )

    entry = json.loads(JsonFormatter().format(record))

    assert "ValueError: boom" in entry["exc_info"]
    assert "Traceback" in entry["exc_info"]


def test_configure_logging_json_format_installs_json_formatter():
    root = logging.getLogger()
    saved_handlers = root.handlers[:]
    saved_level = root.level
    try:
        configure_logging("DEBUG", "json")
        assert root.level == logging.DEBUG
        assert len(root.handlers) == 1
        assert isinstance(root.handlers[0].formatter, JsonFormatter)
    finally:
        root.handlers = saved_handlers
        root.setLevel(saved_level)
