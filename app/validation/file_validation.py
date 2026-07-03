import io

from pypdf import PdfReader
from pypdf.errors import PdfReadError

from app.config import Settings
from app.core.exceptions import (
    EmptyFileError,
    FileTooLargeError,
    TooManyPagesError,
    UnsupportedFileTypeError,
)

PDF_MIME_TYPE = "application/pdf"


def check_content_length(content_length_header: str | None, *, settings: Settings) -> None:
    """Reject an oversized request before its body is read into memory/disk.

    This is a best-effort check based on the Content-Length header (present
    for ordinary multipart uploads). If the header is absent or malformed we
    skip it silently — validate_upload() still catches an oversized body
    after it has been read.
    """

    if content_length_header is None:
        return

    try:
        content_length = int(content_length_header)
    except ValueError:
        return

    if content_length > settings.max_upload_bytes:
        raise FileTooLargeError(
            f"Request body of {content_length} bytes exceeds the "
            f"{settings.max_upload_mb}MB limit.",
            size_bytes=content_length,
            max_bytes=settings.max_upload_bytes,
        )


def validate_upload(
    *, content: bytes, content_type: str | None, settings: Settings
) -> None:
    """Validate an uploaded SDS file before it is sent to Claude.

    Raises AppError subclasses (see app.core.exceptions) on any violation.
    """

    if not content:
        raise EmptyFileError("Uploaded file is empty.")

    if content_type not in settings.allowed_mime_types:
        raise UnsupportedFileTypeError(
            f"Unsupported file type: {content_type!r}. "
            f"Allowed types: {sorted(settings.allowed_mime_types)}.",
            content_type=content_type,
        )

    if len(content) > settings.max_upload_bytes:
        raise FileTooLargeError(
            f"File size {len(content)} bytes exceeds the "
            f"{settings.max_upload_mb}MB limit.",
            size_bytes=len(content),
            max_bytes=settings.max_upload_bytes,
        )

    if content_type == PDF_MIME_TYPE:
        page_count = _count_pdf_pages(content)
        if page_count > settings.max_pdf_pages:
            raise TooManyPagesError(
                f"PDF has {page_count} pages, exceeding the "
                f"{settings.max_pdf_pages}-page limit.",
                page_count=page_count,
                max_pages=settings.max_pdf_pages,
            )


def _count_pdf_pages(content: bytes) -> int:
    try:
        reader = PdfReader(io.BytesIO(content))
        return len(reader.pages)
    except PdfReadError as exc:
        raise UnsupportedFileTypeError(f"Could not parse PDF: {exc}") from exc
