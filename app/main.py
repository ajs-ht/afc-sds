import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.api.v1 import health
from app.api.v1.router import router as v1_router
from app.config import get_settings
from app.core.exceptions import AppError
from app.core.logging import configure_logging

settings = get_settings()
configure_logging(settings.log_level)

logger = logging.getLogger("afc_sds")

app = FastAPI(
    title="AFC-SDS Extraction API",
    description=(
        "SDS (安全データシート / Safety Data Sheet, JIS Z 7253) AI-OCR JSON "
        "extraction API powered by Claude."
    ),
    version="0.1.0",
)

app.include_router(health.router)
app.include_router(v1_router)


@app.exception_handler(AppError)
async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
    if exc.status_code >= 500:
        logger.error("%s: %s (%s)", exc.error_type, exc.message, exc.details)
    else:
        logger.info("%s: %s (%s)", exc.error_type, exc.message, exc.details)

    return JSONResponse(
        status_code=exc.status_code,
        content={"error": {"type": exc.error_type, "message": exc.message}},
    )
