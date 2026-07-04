import anthropic
from fastapi import APIRouter, Depends, Request, UploadFile, File

from app.config import Settings, get_settings
from app.dependencies import verify_api_key
from app.schemas.responses import ErrorResponse, SDSExtractionResponse
from app.services.claude_client import get_claude_client
from app.services.extraction_service import extract_sds
from app.validation.file_validation import check_content_length, validate_upload

router = APIRouter(dependencies=[Depends(verify_api_key)])

# Error statuses this endpoint can return, so they show up in the OpenAPI
# docs with the ErrorResponse shape (see README for the error-type table).
ERROR_RESPONSES = {
    400: {
        "model": ErrorResponse,
        "description": (
            "Invalid upload: unsupported_file_type / file_too_large / "
            "too_many_pages / empty_file"
        ),
    },
    401: {"model": ErrorResponse, "description": "Invalid or missing X-API-Key (unauthorized)"},
    422: {
        "model": ErrorResponse,
        "description": "Claude declined to process the document (extraction_refused)",
    },
    500: {
        "model": ErrorResponse,
        "description": "Server-side configuration or unexpected upstream error (upstream_error)",
    },
    502: {
        "model": ErrorResponse,
        "description": (
            "Claude returned unusable output: extraction_truncated / "
            "extraction_invalid_response"
        ),
    },
    503: {
        "model": ErrorResponse,
        "description": "Anthropic API unreachable, rate limited, or erroring (upstream_error)",
    },
}


@router.post("/sds/extract", response_model=SDSExtractionResponse, responses=ERROR_RESPONSES)
async def extract_sds_endpoint(
    request: Request,
    file: UploadFile = File(...),
    settings: Settings = Depends(get_settings),
    client: anthropic.AsyncAnthropic = Depends(get_claude_client),
) -> SDSExtractionResponse:
    # Reject an oversized request before buffering its body, when possible.
    check_content_length(request.headers.get("content-length"), settings=settings)

    content = await file.read()

    validate_upload(content=content, content_type=file.content_type, settings=settings)

    return await extract_sds(
        content=content,
        content_type=file.content_type,
        client=client,
        settings=settings,
        request_id=request.state.request_id,
    )
