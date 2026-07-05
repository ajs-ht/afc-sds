import base64
import logging
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
from app.schemas.sds import SDS_JSON_SCHEMA, SDSDocument
from app.services.prompts import (
    SYSTEM_PROMPT_BASE,
    SYSTEM_PROMPT_WITH_SCHEMA,
    USER_INSTRUCTION,
)

logger = logging.getLogger("afc_sds.extraction")

_CODE_FENCE_RE = re.compile(r"^```(?:json)?\s*\n?|\n?```\s*$")

PDF_MIME_TYPE = "application/pdf"

STRUCTURED_OUTPUTS_UNAVAILABLE_WARNING = "structured_outputs_unavailable"

# Set to True the first time the API rejects our schema with a compiled-grammar
# size error, so subsequent requests go straight to the prompt-embedded-schema
# fallback instead of paying a doomed 400 round-trip every time. Process-local;
# resets on restart (intentional — the limit may have been raised, or the
# schema shrunk, since the process last ran).
_grammar_too_large = False


def _is_grammar_size_error(exc: anthropic.BadRequestError) -> bool:
    message = str(exc).lower()
    return "grammar" in message and ("too large" in message or "too complex" in message)


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


async def extract_sds(
    *,
    content: bytes,
    content_type: str,
    client: anthropic.AsyncAnthropic,
    settings: Settings,
    request_id: str,
) -> SDSExtractionResponse:
    """Send an SDS file to Claude and return the validated structured JSON.

    Always streams the response (via client.messages.stream) rather than
    calling client.messages.create directly, so large extractions never hit
    the SDK's non-streaming timeout guard regardless of document density.
    Uses the async client so a long extraction never blocks the event loop.

    Output enforcement is two-tier: structured outputs (output_config.format)
    constrain decoding at the API level when available; if the compiled
    grammar exceeds the API's size limit, the request is retried with the
    schema embedded in the prompt instead, and the response is still
    validated with Pydantic either way.
    """

    global _grammar_too_large

    document_block = build_content_block(content, content_type)
    structured = settings.use_structured_outputs and not _grammar_too_large

    try:
        try:
            message = await _stream_message(
                client, settings, document_block, structured=structured
            )
        except anthropic.BadRequestError as exc:
            if not (structured and _is_grammar_size_error(exc)):
                raise
            _grammar_too_large = True
            structured = False
            logger.warning(
                "structured-outputs grammar exceeded the API size limit; "
                "falling back to the prompt-embedded schema for this and "
                "subsequent requests (request_id=%s): %s",
                request_id,
                exc,
            )
            message = await _stream_message(
                client, settings, document_block, structured=False
            )
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
    if settings.use_structured_outputs and not structured:
        warnings.append(STRUCTURED_OUTPUTS_UNAVAILABLE_WARNING)

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


async def _stream_message(
    client: anthropic.AsyncAnthropic,
    settings: Settings,
    document_block: dict,
    *,
    structured: bool,
):
    kwargs: dict = {
        "model": settings.model_id,
        "max_tokens": settings.max_output_tokens,
        "system": [
            {
                "type": "text",
                "text": SYSTEM_PROMPT_BASE if structured else SYSTEM_PROMPT_WITH_SCHEMA,
                "cache_control": {"type": "ephemeral"},
            }
        ],
        "messages": [
            {
                "role": "user",
                "content": [document_block, {"type": "text", "text": USER_INSTRUCTION}],
            }
        ],
    }
    if structured:
        kwargs["output_config"] = {
            "format": {"type": "json_schema", "schema": SDS_JSON_SCHEMA}
        }

    async with client.messages.stream(**kwargs) as stream:
        return await stream.get_final_message()


def _extract_text_block(message) -> str:
    for block in message.content:
        if block.type == "text":
            return block.text
    raise ClaudeResponseInvalidError("Claude's response contained no text content block.")


def _strip_code_fence(text: str) -> str:
    """Strip a wrapping ```json ... ``` fence, if Claude added one.

    On the prompt-embedded-schema fallback path (no constrained decoding),
    Claude occasionally wraps its JSON in a markdown code fence despite being
    told not to; this undoes that so model_validate_json sees raw JSON. On
    the structured-outputs path the fence can't occur, and this is a no-op.
    """
    text = text.strip()
    if text.startswith("```"):
        text = _CODE_FENCE_RE.sub("", text).strip()
    return text
