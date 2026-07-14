import pytest
from pydantic import ValidationError

from app.config import Settings


def test_defaults_apply(monkeypatch):
    monkeypatch.delenv("MODEL_ID", raising=False)
    settings = Settings(anthropic_api_key="k", api_key="s")

    assert settings.model_id == "claude-opus-4-8"
    assert settings.max_upload_mb == 32
    assert settings.max_pdf_pages == 50
    assert settings.max_output_tokens == 24000
    assert "application/pdf" in settings.allowed_mime_types


def test_max_upload_bytes_conversion():
    settings = Settings(anthropic_api_key="k", api_key="s", max_upload_mb=1)
    assert settings.max_upload_bytes == 1024 * 1024


def test_missing_required_keys_fail_fast(monkeypatch):
    # ANTHROPIC_API_KEY / API_KEY have no defaults on purpose: a misconfigured
    # deployment must fail at startup, not at the first request.
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    monkeypatch.delenv("API_KEY", raising=False)

    with pytest.raises(ValidationError) as excinfo:
        Settings(_env_file=None)

    missing = {error["loc"][0] for error in excinfo.value.errors()}
    assert missing == {"anthropic_api_key", "api_key"}


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("true", True), ("1", True), ("false", False), ("0", False)],
)
def test_use_structured_outputs_env_parsing(monkeypatch, raw, expected):
    monkeypatch.setenv("USE_STRUCTURED_OUTPUTS", raw)
    settings = Settings(anthropic_api_key="k", api_key="s", _env_file=None)
    assert settings.use_structured_outputs is expected


def test_allowed_mime_types_default_set():
    # The whole upload-validation layer keys off this set; it has no env-var
    # counterpart, so a change here is a deliberate code change.
    settings = Settings(anthropic_api_key="k", api_key="s")
    assert settings.allowed_mime_types == frozenset(
        {"application/pdf", "image/png", "image/jpeg", "image/webp"}
    )
