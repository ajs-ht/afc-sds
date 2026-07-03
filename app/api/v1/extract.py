import uuid

import anthropic
from fastapi import APIRouter, Depends, Request, UploadFile, File

from app.config import Settings, get_settings
from app.dependencies import verify_api_key
from app.schemas.responses import SDSExtractionResponse
from app.services.claude_client import get_claude_client
from app.services.extraction_service import extract_sds
from app.validation.file_validation import check_content_length, validate_upload

router = APIRouter(dependencies=[Depends(verify_api_key)])


@router.post("/sds/extract", response_model=SDSExtractionResponse)
async def extract_sds_endpoint(
    request: Request,
    file: UploadFile = File(...),
    settings: Settings = Depends(get_settings),
    client: anthropic.Anthropic = Depends(get_claude_client),
) -> SDSExtractionResponse:
    # Reject an oversized request before buffering its body, when possible.
    check_content_length(request.headers.get("content-length"), settings=settings)

    content = await file.read()

    validate_upload(content=content, content_type=file.content_type, settings=settings)

    request_id = str(uuid.uuid4())

    return extract_sds(
        content=content,
        content_type=file.content_type,
        client=client,
        settings=settings,
        request_id=request_id,
    )
