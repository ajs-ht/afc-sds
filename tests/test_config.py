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
