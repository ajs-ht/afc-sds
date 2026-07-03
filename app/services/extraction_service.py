import base64
import re

import anthropic
from pydantic import ValidationError

from app.config import Settings
from app.core.exceptions import (
    ClaudeRefusalError,
    ClaudeResponseInvalidError,
    ClaudeTruncatedError,
    ClaudeUpstreamError,
)
from app.core.logging import log_usage
from app.schemas.responses import ExtractionUsage, SDSExtractionResponse
from app.schemas.sds import SDSDocument
from app.services.prompts import SYSTEM_PROMPT, USER_INSTRUCTION

_CODE_FENCE_RE = re.compile(r"^```(?:json)?\s*\n?|\n?```\s*$")

PDF_MIME_TYPE = "application/pdf"


def build_content_block(content: bytes, content_type: str) -> dict:
    data = base64.b64encode(content).decode("ascii")
    if content_type == PDF_MIME_TYPE:
        return {
            "type": "document",
            "source": {"type": "base64", "media_type": PDF_MIME_TYPE, "data": data},
        }
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": content_type, "data": data},
    }


def extract_sds(
    *,
    content: bytes,
    content_type: str,
    client: anthropic.Anthropic,
    settings: Settings,
    request_id: str,
) -> SDSExtractionResponse:
    """Send an SDS file to Claude and return the validated structured JSON.

    Always streams the response (via client.messages.stream) rather than
    calling client.messages.create directly, so large extractions never hit
    the SDK's non-streaming timeout guard regardless of document density.
    """

    document_block = build_content_block(content, content_type)

    try:
        with client.messages.stream(
            model=settings.model_id,
            max_tokens=settings.max_output_tokens,
            system=[
                {
                    "type": "text",
                    "text": SYSTEM_PROMPT,
                    "cache_control": {"type": "ephemeral"},
                }
            ],
            messages=[
                {
                    "role": "user",
                    "content": [document_block, {"type": "text", "text": USER_INSTRUCTION}],
                }
            ],
        ) as stream:
            message = stream.get_final_message()
    except anthropic.RateLimitError as exc:
        raise ClaudeUpstreamError(503, "Rate limited by the Anthropic API.") from exc
    except (anthropic.APIConnectionError, anthropic.APITimeoutError) as exc:
        # APITimeoutError subclasses APIConnectionError; catching both here
        # is defensive against SDK versions where that isn't guaranteed.
        raise ClaudeUpstreamError(503, "Could not reach the Anthropic API.") from exc
    except anthropic.InternalServerError as exc:
        raise ClaudeUpstreamError(503, "The Anthropic API returned a server error.") from exc
    except (
        anthropic.AuthenticationError,
        anthropic.PermissionDeniedError,
        anthropic.NotFoundError,
    ) as exc:
        # Our own misconfiguration (bad key, bad model id) — not the caller's fault.
        raise ClaudeUpstreamError(500, "Anthropic API request was rejected due to a server-side configuration issue.") from exc
    except anthropic.APIStatusError as exc:
        raise ClaudeUpstreamError(500, f"Unexpected Anthropic API error: {exc}") from exc

    usage = message.usage
    cache_creation = getattr(usage, "cache_creation_input_tokens", 0) or 0
    cache_read = getattr(usage, "cache_read_input_tokens", 0) or 0
    log_usage(
        request_id=request_id,
        model=message.model,
        stop_reason=message.stop_reason,
        input_tokens=usage.input_tokens,
        output_tokens=usage.output_tokens,
        cache_creation_input_tokens=cache_creation,
        cache_read_input_tokens=cache_read,
    )

    if message.stop_reason == "refusal":
        details = message.stop_details
        raise ClaudeRefusalError(
            "Claude declined to process this document.",
            category=getattr(details, "category", None) if details else None,
        )

    text = _strip_code_fence(_extract_text_block(message))

    try:
        parsed = SDSDocument.model_validate_json(text)
    except (ValidationError, ValueError) as exc:
        if message.stop_reason == "max_tokens":
            raise ClaudeTruncatedError(
                "Claude's response was truncated (max_tokens) before completing valid JSON."
            ) from exc
        raise ClaudeResponseInvalidError(
            f"Claude's response did not match the expected SDS schema: {exc}"
        ) from exc

    warnings: list[str] = []
    if message.stop_reason == "max_tokens":
        warnings.append("output_truncated_max_tokens")

    return SDSExtractionResponse(
        data=parsed,
        warnings=warnings,
        model=message.model,
        usage=ExtractionUsage(
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            cache_creation_input_tokens=cache_creation,
            cache_read_input_tokens=cache_read,
        ),
    )


def _extract_text_block(message) -> str:
    for block in message.content:
        if block.type == "text":
            return block.text
    raise ClaudeResponseInvalidError("Claude's response contained no text content block.")


def _strip_code_fence(text: str) -> str:
    """Strip a wrapping ```json ... ``` fence, if Claude added one.

    Without structured-outputs constrained decoding (see prompts.py), Claude
    occasionally wraps its JSON in a markdown code fence despite being told
    not to; this undoes that so model_validate_json sees raw JSON.
    """
    text = text.strip()
    if text.startswith("```"):
        text = _CODE_FENCE_RE.sub("", text).strip()
    return text
