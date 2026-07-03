from functools import lru_cache

import anthropic

from app.config import get_settings


@lru_cache
def get_claude_client() -> anthropic.Anthropic:
    settings = get_settings()
    return anthropic.Anthropic(api_key=settings.anthropic_api_key)
