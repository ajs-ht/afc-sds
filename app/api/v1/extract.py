import anthropic
from fastapi import APIRouter, Depends, Form, Request, UploadFile, File

from app.config import Settings, get_settings
from app.dependencies import verify_api_key
from app.schemas.responses import ErrorResponse, SDSExtractionResponse
from app.services.claude_client import get_claude_client
from app.services.extraction_service import extract_sds
from app.validation.file_validation import (
    check_content_length,
    slice_pdf_pages,
    validate_upload,
)

router = APIRouter(dependencies=[Depends(verify_api_key)])

# Error statuses this endpoint can return, so they show up in the OpenAPI
# docs with the ErrorResponse shape (see README for the error-type table).
ERROR_RESPONSES = {
    400: {
        "model": ErrorResponse,
        "description": (
            "Invalid upload: unsupported_file_type / file_too_large / "
            "too_many_pages / empty_file / invalid_page_range, or Claude "
            "rejected the document itself (invalid_document)"
        ),
    },
    401: {"model": ErrorResponse, "description": "Invalid or missing X-API-Key (unauthorized)"},
    422: {
        "model": ErrorResponse,
        "description": (
            "Claude declined to process the document (extraction_refused), "
            "or the request failed validation (validation_error)"
        ),
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
    pages: str | None = Form(
        default=None,
        description=(
            'Optional 1-based page selection for PDF uploads: "6" or "6-11" '
            "(inclusive). Use it to re-extract the further SDS documents "
            "reported in `additional_documents` of a multi-SDS file."
        ),
    ),
    settings: Settings = Depends(get_settings),
    client: anthropic.AsyncAnthropic = Depends(get_claude_client),
) -> SDSExtractionResponse:
    # Reject an oversized request before buffering its body, when possible.
    check_content_length(request.headers.get("content-length"), settings=settings)

    content = await file.read()

    validate_upload(content=content, content_type=file.content_type, settings=settings)

    if pages is not None:
        content = slice_pdf_pages(content, file.content_type, pages)

    return await extract_sds(
        content=content,
        content_type=file.content_type,
        client=client,
        settings=settings,
        request_id=request.state.request_id,
    )
