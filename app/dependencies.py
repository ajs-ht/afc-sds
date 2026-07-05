import secrets

from fastapi import Header

from app.config import Settings, get_settings
from app.core.exceptions import UnauthorizedError


async def verify_api_key(
    x_api_key: str | None = Header(default=None, alias="X-API-Key"),
) -> None:
    """Reject the request unless X-API-Key matches, via constant-time compare."""

    settings: Settings = get_settings()

    if x_api_key is None or not secrets.compare_digest(x_api_key, settings.api_key):
        raise UnauthorizedError("Invalid or missing X-API-Key header.")
