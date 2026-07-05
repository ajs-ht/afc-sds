import io

import pytest
from pypdf import PdfReader, PdfWriter

from app.config import Settings
from app.core.exceptions import (
    EmptyFileError,
    FileTooLargeError,
    InvalidPageRangeError,
    TooManyPagesError,
    UnsupportedFileTypeError,
)
from app.validation.file_validation import (
    check_content_length,
    slice_pdf_pages,
    validate_upload,
)


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


def test_spoofed_pdf_content_type_rejected(settings):
    with pytest.raises(UnsupportedFileTypeError):
        validate_upload(
            content=b"this is not a pdf", content_type="application/pdf", settings=settings
        )


def test_spoofed_png_content_type_rejected(settings):
    with pytest.raises(UnsupportedFileTypeError):
        validate_upload(content=b"not a png either", content_type="image/png", settings=settings)


def test_valid_webp_passes(settings):
    validate_upload(
        content=b"RIFF" + b"\x00\x00\x00\x00" + b"WEBP" + b"0" * 100,
        content_type="image/webp",
        settings=settings,
    )


def test_riff_without_webp_fourcc_rejected(settings):
    with pytest.raises(UnsupportedFileTypeError):
        validate_upload(
            content=b"RIFF" + b"\x00\x00\x00\x00" + b"AVI " + b"0" * 100,
            content_type="image/webp",
            settings=settings,
        )


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


# --- slice_pdf_pages ---------------------------------------------------------


def _pdf_with_pages(count: int) -> bytes:
    writer = PdfWriter()
    for _ in range(count):
        writer.add_blank_page(width=595, height=842)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def test_slice_pdf_pages_range():
    sliced = slice_pdf_pages(_pdf_with_pages(11), "application/pdf", "6-11")
    assert len(PdfReader(io.BytesIO(sliced)).pages) == 6


def test_slice_pdf_pages_single_page():
    sliced = slice_pdf_pages(_pdf_with_pages(3), "application/pdf", "2")
    assert len(PdfReader(io.BytesIO(sliced)).pages) == 1


def test_slice_pdf_pages_full_range():
    sliced = slice_pdf_pages(_pdf_with_pages(3), "application/pdf", "1-3")
    assert len(PdfReader(io.BytesIO(sliced)).pages) == 3


@pytest.mark.parametrize("spec", ["0", "abc", "5-2", "1-", "-3", "1,3", ""])
def test_slice_pdf_pages_malformed_spec_rejected(spec):
    with pytest.raises(InvalidPageRangeError):
        slice_pdf_pages(_pdf_with_pages(5), "application/pdf", spec)


@pytest.mark.parametrize("spec", ["6", "2-6", "0-3"])
def test_slice_pdf_pages_out_of_bounds_rejected(spec):
    with pytest.raises(InvalidPageRangeError):
        slice_pdf_pages(_pdf_with_pages(5), "application/pdf", spec)


def test_slice_pdf_pages_rejected_for_non_pdf():
    with pytest.raises(InvalidPageRangeError):
        slice_pdf_pages(b"\x89PNG...", "image/png", "1-2")


def test_check_content_length_rejects_oversized_header(settings):
    with pytest.raises(FileTooLargeError):
        check_content_length(str(settings.max_upload_bytes + 1), settings=settings)


def test_check_content_length_allows_within_limit(settings):
    check_content_length(str(settings.max_upload_bytes), settings=settings)


def test_check_content_length_skips_missing_header(settings):
    check_content_length(None, settings=settings)


def test_check_content_length_skips_malformed_header(settings):
    check_content_length("not-a-number", settings=settings)
