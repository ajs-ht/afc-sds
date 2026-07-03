"""Custom exceptions and their HTTP status/error-type mapping.

Each exception carries the HTTP status code and a machine-readable error
`type` string so the FastAPI exception handler (see app/main.py) can map it
to a consistent JSON error body without per-route try/except blocks.
"""

from __future__ import annotations


class AppError(Exception):
    """Base class for all application errors that map to an HTTP response."""

    status_code: int = 500
    error_type: str = "internal_error"

    def __init__(self, message: str, **details: object) -> None:
        super().__init__(message)
        self.message = message
        self.details = details


class UnsupportedFileTypeError(AppError):
    status_code = 400
    error_type = "unsupported_file_type"


class FileTooLargeError(AppError):
    status_code = 400
    error_type = "file_too_large"


class TooManyPagesError(AppError):
    status_code = 400
    error_type = "too_many_pages"


class EmptyFileError(AppError):
    status_code = 400
    error_type = "empty_file"


class UnauthorizedError(AppError):
    status_code = 401
    error_type = "unauthorized"


class ClaudeRefusalError(AppError):
    """Claude declined to process the document for safety reasons."""

    status_code = 422
    error_type = "extraction_refused"


class ClaudeTruncatedError(AppError):
    """Claude's response hit max_tokens before producing valid JSON."""

    status_code = 502
    error_type = "extraction_truncated"


class ClaudeUpstreamError(AppError):
    """The Anthropic API returned an error we could not recover from."""

    error_type = "upstream_error"

    def __init__(self, status_code: int, message: str, **details: object) -> None:
        super().__init__(message, **details)
        self.status_code = status_code


class ClaudeResponseInvalidError(AppError):
    """Claude's response did not validate against the SDS JSON schema."""

    status_code = 502
    error_type = "extraction_invalid_response"
