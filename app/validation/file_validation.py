import io
import re

from pypdf import PdfReader, PdfWriter
from pypdf.errors import PdfReadError

from app.config import Settings
from app.core.exceptions import (
    EmptyFileError,
    FileTooLargeError,
    InvalidPageRangeError,
    TooManyPagesError,
    UnsupportedFileTypeError,
)

PDF_MIME_TYPE = "application/pdf"

# `pages` form field: "6" (one page) or "6-11" (inclusive range), 1-based.
_PAGES_SPEC_RE = re.compile(r"^\s*(\d+)\s*(?:-\s*(\d+)\s*)?$")

# Magic-byte signatures for the MIME types this API accepts, so a spoofed
# Content-Type header can't smuggle unexpected content past validate_upload().
# WEBP is a RIFF container: the fourCC at byte offset 8 identifies it.
_MAGIC_SIGNATURES: dict[str, tuple[bytes, ...]] = {
    "application/pdf": (b"%PDF-",),
    "image/png": (b"\x89PNG\r\n\x1a\n",),
    "image/jpeg": (b"\xff\xd8\xff",),
    "image/webp": (b"RIFF",),
}


def _has_valid_signature(content: bytes, content_type: str) -> bool:
    signatures = _MAGIC_SIGNATURES.get(content_type)
    if signatures is None:
        return True
    if not any(content.startswith(sig) for sig in signatures):
        return False
    if content_type == "image/webp":
        return content[8:12] == b"WEBP"
    return True


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

    if not _has_valid_signature(content, content_type):
        raise UnsupportedFileTypeError(
            f"File content does not match the declared type {content_type!r}.",
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


def slice_pdf_pages(content: bytes, content_type: str | None, pages_spec: str) -> bytes:
    """Return a new PDF containing only the pages named by `pages_spec`.

    `pages_spec` is "N" or "N-M" (1-based, inclusive) — the format callers
    get back in `additional_documents` for multi-SDS files. Raises
    InvalidPageRangeError for a non-PDF upload, a malformed spec, or a range
    outside the document.
    """

    if content_type != PDF_MIME_TYPE:
        raise InvalidPageRangeError(
            "The `pages` parameter is only supported for PDF uploads.",
            content_type=content_type,
        )

    match = _PAGES_SPEC_RE.fullmatch(pages_spec)
    if not match:
        raise InvalidPageRangeError(
            f"Invalid `pages` value: {pages_spec!r}. Use \"6\" or \"6-11\" (1-based, inclusive).",
            pages=pages_spec,
        )
    start = int(match.group(1))
    end = int(match.group(2)) if match.group(2) else start

    try:
        reader = PdfReader(io.BytesIO(content))
    except PdfReadError as exc:
        raise UnsupportedFileTypeError(f"Could not parse PDF: {exc}") from exc

    page_count = len(reader.pages)
    if not (1 <= start <= end <= page_count):
        raise InvalidPageRangeError(
            f"Page range {start}-{end} is out of bounds for a {page_count}-page PDF.",
            pages=pages_spec,
            page_count=page_count,
        )

    writer = PdfWriter()
    for index in range(start - 1, end):
        writer.add_page(reader.pages[index])
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def _count_pdf_pages(content: bytes) -> int:
    try:
        reader = PdfReader(io.BytesIO(content))
        return len(reader.pages)
    except PdfReadError as exc:
        raise UnsupportedFileTypeError(f"Could not parse PDF: {exc}") from exc
