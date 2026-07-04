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

    # Constrained decoding via Claude's structured outputs (output_config.format).
    # When the compiled grammar exceeds the API's size limit, the service falls
    # back to the prompt-embedded-schema mode automatically; set this to False
    # to skip structured outputs entirely.
    use_structured_outputs: bool = True

    log_level: str = "INFO"
    log_format: Literal["text", "json"] = "text"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    @property
    def max_upload_bytes(self) -> int:
        return self.max_upload_mb * 1024 * 1024


@lru_cache
def get_settings() -> Settings:
    return Settings()
