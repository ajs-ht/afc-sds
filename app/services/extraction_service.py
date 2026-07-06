"""Send an SDS file to Claude and return a schema-validated result.

Output enforcement is two-tier: structured outputs (output_config.format)
constrain decoding at the API level when enabled and the compiled grammar
fits within the API's size limit; otherwise (or when disabled) the schema is
embedded in the system prompt instead, and the response is validated with
Pydantic either way. A structured-outputs request that first hits the
grammar-size limit falls back to the prompt-embedded schema automatically
and flips the process-local `_grammar_too_large` flag so later requests skip
straight to the fallback. A response that fails Pydantic validation (and
wasn't truncated by max_tokens) is retried once before giving up. See
app/services/prompts.py for why structured outputs currently can't host the
full SDS schema.
"""

import base64
import logging
import re

import anthropic
from pydantic import ValidationError

from app.config import Settings
from app.core.exceptions import (
    ClaudeInvalidDocumentError,
    ClaudeRefusalError,
    ClaudeResponseInvalidError,
    ClaudeTruncatedError,
    ClaudeUpstreamError,
)
from app.core.logging import log_usage
from app.schemas.responses import ExtractionUsage, SDSExtractionResponse
from app.schemas.sds import SDS_JSON_SCHEMA, SDSDocument
from app.services.postvalidation import collect_domain_warnings
from app.services.prompts import (
    SYSTEM_PROMPT_BASE,
    SYSTEM_PROMPT_WITH_SCHEMA,
    USER_INSTRUCTION,
)

logger = logging.getLogger("afc_sds.extraction")

_CODE_FENCE_RE = re.compile(r"^```(?:json)?\s*\n?|\n?```\s*$")

PDF_MIME_TYPE = "application/pdf"

STRUCTURED_OUTPUTS_UNAVAILABLE_WARNING = "structured_outputs_unavailable"
RETRIED_INVALID_RESPONSE_WARNING = "retried_invalid_response"
ADDITIONAL_SDS_DOCUMENTS_WARNING = "additional_sds_documents_detected"

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

    A response that fails Pydantic validation (without having been truncated
    by max_tokens) is retried once before surfacing extraction_invalid_response
    — on the prompt-embedded-schema path nothing constrains decoding, so a
    one-off malformed response is recoverable. The returned usage covers all
    API calls made for the request.
    """

    document_block = build_content_block(content, content_type)
    structured = settings.use_structured_outputs and not _grammar_too_large

    message, structured = await _request_extraction(
        client, settings, document_block, structured=structured, request_id=request_id
    )
    api_messages = [message]
    parsed, retried = _parse_document(message)

    if parsed is None:
        logger.warning(
            "response failed SDS schema validation; retrying once (request_id=%s)",
            request_id,
        )
        message, structured = await _request_extraction(
            client, settings, document_block, structured=structured, request_id=request_id
        )
        api_messages.append(message)
        parsed, _ = _parse_document(message, final_attempt=True)

    warnings: list[str] = []
    if message.stop_reason == "max_tokens":
        warnings.append("output_truncated_max_tokens")
    if retried:
        warnings.append(RETRIED_INVALID_RESPONSE_WARNING)
    if settings.use_structured_outputs and not structured:
        warnings.append(STRUCTURED_OUTPUTS_UNAVAILABLE_WARNING)
    if parsed.additional_documents:
        # Multi-SDS file: only the first SDS was extracted. Callers re-fetch
        # the rest via the `pages` form field using the reported page ranges.
        warnings.append(ADDITIONAL_SDS_DOCUMENTS_WARNING)
    warnings.extend(collect_domain_warnings(parsed))

    return SDSExtractionResponse(
        data=parsed,
        warnings=warnings,
        model=message.model,
        usage=_sum_usage(api_messages),
    )


async def _request_extraction(
    client: anthropic.AsyncAnthropic,
    settings: Settings,
    document_block: dict,
    *,
    structured: bool,
    request_id: str,
):
    """One Claude call: grammar-size fallback, SDK exception mapping, usage log.

    Returns (message, structured) — `structured` flips to False when the call
    fell back to the prompt-embedded schema mid-flight.
    """

    global _grammar_too_large

    try:
        try:
            message = await _stream_message(
                client, settings, document_block, structured=structured
            )
        except anthropic.BadRequestError as exc:
            if not (structured and _is_grammar_size_error(exc)):
                # Not a grammar-size fallback case: the document/request itself
                # was rejected, which is the caller's fault, not a server error.
                raise ClaudeInvalidDocumentError(
                    f"Anthropic rejected the document as invalid: {exc}"
                ) from exc
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
    log_usage(
        request_id=request_id,
        model=message.model,
        stop_reason=message.stop_reason,
        input_tokens=usage.input_tokens,
        output_tokens=usage.output_tokens,
        cache_creation_input_tokens=getattr(usage, "cache_creation_input_tokens", 0) or 0,
        cache_read_input_tokens=getattr(usage, "cache_read_input_tokens", 0) or 0,
    )

    if message.stop_reason == "refusal":
        details = message.stop_details
        raise ClaudeRefusalError(
            "Claude declined to process this document.",
            category=getattr(details, "category", None) if details else None,
        )

    return message, structured


def _parse_document(message, *, final_attempt: bool = False) -> tuple[SDSDocument | None, bool]:
    """Validate a message's text as an SDSDocument.

    Returns (document, needs_retry_marker). A validation failure on a
    non-truncated response returns (None, True) so the caller can retry once;
    with final_attempt=True it raises instead. Truncation always raises —
    retrying the same document would just truncate again.
    """

    text = _strip_code_fence(_extract_text_block(message))
    try:
        return SDSDocument.model_validate_json(text), False
    except (ValidationError, ValueError) as exc:
        if message.stop_reason == "max_tokens":
            raise ClaudeTruncatedError(
                "Claude's response was truncated (max_tokens) before completing valid JSON."
            ) from exc
        if final_attempt:
            raise ClaudeResponseInvalidError(
                "Claude's response did not match the expected SDS schema "
                f"(even after one retry): {exc}"
            ) from exc
        return None, True


def _sum_usage(messages) -> ExtractionUsage:
    return ExtractionUsage(
        input_tokens=sum(m.usage.input_tokens for m in messages),
        output_tokens=sum(m.usage.output_tokens for m in messages),
        cache_creation_input_tokens=sum(
            getattr(m.usage, "cache_creation_input_tokens", 0) or 0 for m in messages
        ),
        cache_read_input_tokens=sum(
            getattr(m.usage, "cache_read_input_tokens", 0) or 0 for m in messages
        ),
    )


async def _stream_message(
    client: anthropic.AsyncAnthropic,
    settings: Settings,
    document_block: dict,
    *,
    structured: bool,
):
    # Note: sampling params (temperature/top_p/top_k) are intentionally absent —
    # they are removed on Opus 4.7+ models and the API rejects them with a 400
    # ("`temperature` is deprecated for this model"). Extraction consistency is
    # carried by the transcription-style prompt instead. This assumption was
    # only verified against Opus; re-check it if settings.model_id (MODEL_ID)
    # points at a different model family (e.g. claude-sonnet-5).
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
