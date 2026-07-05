from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    anthropic_api_key: str
    model_id: str = "claude-opus-4-8"

    api_key: str

    max_upload_mb: int = 32
    allowed_mime_types: frozenset[str] = frozenset(
        {"application/pdf", "image/png", "image/jpeg", "image/webp"}
    )
    max_pdf_pages: int = 50

    max_output_tokens: int = 24000

    # Sampling temperature for extraction. 0.0 keeps the transcription-style
    # output deterministic and reproducible; raise only for experimentation.
    temperature: float = 0.0

    # Constrained decoding via Claude's structured outputs (output_config.format).
    # Off by default: as of 2026-07 the SDS schema exceeds the API's
    # compiled-grammar limits no matter how it is slimmed down (see the
    # prompts.py docstring), so enabling this just costs one 400 round-trip
    # per process before the automatic fallback kicks in. Flip it on to
    # re-test after Anthropic raises the limit — the fallback makes that safe.
    use_structured_outputs: bool = False

    log_level: str = "INFO"
    log_format: Literal["text", "json"] = "text"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    @property
    def max_upload_bytes(self) -> int:
        return self.max_upload_mb * 1024 * 1024


@lru_cache
def get_settings() -> Settings:
    return Settings()
