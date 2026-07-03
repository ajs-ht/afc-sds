import pytest

from app.config import Settings
from app.core.exceptions import (
    EmptyFileError,
    FileTooLargeError,
    TooManyPagesError,
    UnsupportedFileTypeError,
)
from app.validation.file_validation import check_content_length, validate_upload


@pytest.fixture
def settings() -> Settings:
    return Settings(
        anthropic_api_key="k",
        api_key="s",
        max_upload_mb=1,
        max_pdf_pages=5,
    )


def test_valid_pdf_passes(sample_pdf_bytes, settings):
    validate_upload(content=sample_pdf_bytes, content_type="application/pdf", settings=settings)


def test_valid_image_passes(settings):
    validate_upload(content=b"\x89PNG\r\n\x1a\n" + b"0" * 100, content_type="image/png", settings=settings)


def test_empty_file_rejected(settings):
    with pytest.raises(EmptyFileError):
        validate_upload(content=b"", content_type="application/pdf", settings=settings)


def test_unsupported_mime_type_rejected(sample_pdf_bytes, settings):
    with pytest.raises(UnsupportedFileTypeError):
        validate_upload(content=sample_pdf_bytes, content_type="text/plain", settings=settings)


def test_missing_content_type_rejected(sample_pdf_bytes, settings):
    with pytest.raises(UnsupportedFileTypeError):
        validate_upload(content=sample_pdf_bytes, content_type=None, settings=settings)


def test_oversized_file_rejected(settings):
    oversized = b"%PDF-1.4\n" + b"0" * (settings.max_upload_bytes + 1)
    with pytest.raises(FileTooLargeError):
        validate_upload(content=oversized, content_type="application/pdf", settings=settings)


def test_pdf_page_count_within_limit(sample_pdf_bytes, settings):
    # sample_sds.pdf is a single page — must pass with max_pdf_pages=5.
    validate_upload(content=sample_pdf_bytes, content_type="application/pdf", settings=settings)


def test_pdf_page_count_over_limit_rejected(sample_pdf_bytes, settings):
    settings = settings.model_copy(update={"max_pdf_pages": 0})
    with pytest.raises(TooManyPagesError):
        validate_upload(content=sample_pdf_bytes, content_type="application/pdf", settings=settings)


def test_check_content_length_rejects_oversized_header(settings):
    with pytest.raises(FileTooLargeError):
        check_content_length(str(settings.max_upload_bytes + 1), settings=settings)


def test_check_content_length_allows_within_limit(settings):
    check_content_length(str(settings.max_upload_bytes), settings=settings)


def test_check_content_length_skips_missing_header(settings):
    check_content_length(None, settings=settings)


def test_check_content_length_skips_malformed_header(settings):
    check_content_length("not-a-number", settings=settings)
