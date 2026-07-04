import logging
import time
import uuid

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.api.v1 import health
from app.api.v1.router import router as v1_router
from app.config import get_settings
from app.core.exceptions import AppError
from app.core.logging import configure_logging, log_access

settings = get_settings()
configure_logging(settings.log_level, settings.log_format)

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


@app.middleware("http")
async def request_context_middleware(request: Request, call_next):
    """Assign a request_id to every request and log method/path/status/latency.

    The same request_id is passed down to the extraction usage log and
    returned in the X-Request-ID response header (and in error bodies), so a
    client-reported failure can be correlated with server logs.
    """
    request_id = str(uuid.uuid4())
    request.state.request_id = request_id

    start = time.perf_counter()
    response = await call_next(request)
    duration_ms = (time.perf_counter() - start) * 1000

    response.headers["X-Request-ID"] = request_id
    log_access(
        request_id=request_id,
        method=request.method,
        path=request.url.path,
        status_code=response.status_code,
        duration_ms=duration_ms,
    )
    return response


@app.exception_handler(AppError)
async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
    request_id = getattr(request.state, "request_id", None)

    if exc.status_code >= 500:
        logger.error(
            "%s: %s (%s) request_id=%s", exc.error_type, exc.message, exc.details, request_id
        )
    else:
        logger.info(
            "%s: %s (%s) request_id=%s", exc.error_type, exc.message, exc.details, request_id
        )

    return JSONResponse(
        status_code=exc.status_code,
        content={
            "error": {
                "type": exc.error_type,
                "message": exc.message,
                "request_id": request_id,
            }
        },
    )
