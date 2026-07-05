# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

API-only FastAPI application (no UI/CLI) that performs AI-OCR on SDS documents (安全データシート / Safety Data Sheets, JIS Z 7253) using the Claude API and returns structured JSON following the standard 16-section JIS Z 7253 layout. The domain, prompts, and README are in Japanese; keep that convention when editing them.

## Commands

```bash
pip install -e ".[dev]"        # install with dev dependencies

pytest                         # unit tests (no real API key needed; integration tests excluded by default via pyproject addopts)
pytest tests/test_schema.py    # run one test file
pytest tests/test_extraction_service.py::test_name   # run one test

# Integration tests (call the real Anthropic API)
RUN_INTEGRATION=1 ANTHROPIC_API_KEY=sk-ant-... pytest -m integration

# Real-world SDS sample verification (drop PDFs/images into tests/fixtures/real_world/ first;
# results are written to tests/fixtures/real_world/_results/ — both are gitignored).
# Optional: put <name>.expected.json ground truth next to a sample to get a
# field-level accuracy report in _results/<name>.accuracy.json.
RUN_INTEGRATION=1 ANTHROPIC_API_KEY=sk-ant-... pytest -m integration tests/integration/test_real_world_extraction.py -s

# Run the server
docker compose up --build      # needs .env (copy from .env.example: ANTHROPIC_API_KEY + API_KEY)
uvicorn app.main:app           # local, without Docker
```

There is no linter or formatter configured.

## Architecture

Request flow for `POST /v1/sds/extract`:

1. `app/main.py` — middleware assigns a `request_id` (returned as `X-Request-ID`, threaded through all logs and error bodies) and a single `AppError` exception handler renders every error as `{"error": {"type", "message", "request_id"}}`.
2. `app/dependencies.py` — `X-API-Key` shared-secret auth (constant-time compare), applied router-wide in `app/api/v1/router.py`.
3. `app/validation/file_validation.py` — size/MIME/PDF-page-count checks (Content-Length pre-check before buffering, then re-check after read). Also hosts `slice_pdf_pages` backing the optional `pages` form field ("6" / "6-11", 1-based inclusive) used to re-extract the further documents of a multi-SDS PDF.
4. `app/services/extraction_service.py` — sends the file to Claude (base64 document/image block; no sampling params — they 400 on Opus 4.7+) and validates the response with Pydantic; a validation failure not caused by max_tokens truncation is retried once (`retried_invalid_response` warning, usage summed across both calls). **Always streams** (`client.messages.stream`) so long extractions never trip the SDK's non-streaming timeout, and uses `AsyncAnthropic` so the event loop isn't blocked.
5. `app/services/postvalidation.py` — deterministic domain checks on the validated document (CAS check digit, GHS01–09 pictogram vocabulary, leading H/P-code format, UN-number format); violations become `warnings` entries, never rejections. Explicit-absence notations common in Japanese SDS (非該当, 非開示, 適用外, ...) are allowlisted — a CAS/UN field holding one is faithful transcription, not a misread. New warning kinds also go in the README warnings table.
6. `app/schemas/sds.py` — `SDSDocument`, the 16-section output model. `SDS_JSON_SCHEMA` is computed once at import time.

### Output enforcement: two-tier with automatic fallback

This is the most nuanced part of the codebase — read the docstrings in `app/services/prompts.py` and `extraction_service.py` before touching it.

- **Default (operating mode)**: the JSON schema is embedded in the system prompt (`SYSTEM_PROMPT_WITH_SCHEMA`) and the response is strictly validated with Pydantic afterwards.
- **Structured outputs** (`output_config.format`, opt-in via `USE_STRUCTURED_OUTPUTS=true`) currently **cannot host the SDS schema**: the API's compiled-grammar limit rejects it no matter how it is slimmed (verified against the live API 2026-07 — even a flat object with 20 optional fields fails). When enabled, a grammar-size 400 flips the process-local `_grammar_too_large` flag, retries with the prompt-embedded schema, and appends the `structured_outputs_unavailable` warning. Tests reset this flag via an autouse fixture in `tests/conftest.py`.
- Both system-prompt variants are byte-stable and sent with `cache_control: {"type": "ephemeral"}` for prompt caching — don't introduce per-request variation into them.

### Schema is a versioned contract

`SDSDocument` is served verbatim at `GET /v1/sds/schema` with `SCHEMA_VERSION` (`app/schemas/sds.py`); downstream systems generate types from it. Any change to the output shape requires bumping `SCHEMA_VERSION` and updating the README. Conventions baked into the schema:

- All models inherit `StrictModel` (`app/schemas/common.py`, `extra="forbid"`) — this emits `additionalProperties: false`, required by structured outputs and load-bearing on the fallback path to reject fabricated fields.
- **All measured values are strings**, never numbers, so ranges/units/qualifiers survive verbatim ("10~20%", "約35℃(密閉式)").
- Sections 1–3, 8, 9, 14, 15 have dedicated structured fields; the rest use the generic `SDSSection`. Every section keeps a faithful-text `content_markdown` / `raw_text` field — structured fields are a projection, not a replacement, because SDS content is safety-critical and must not be lossily condensed.
- Multi-SDS files: extraction always targets the **first** SDS; the model reports the rest in `additional_documents` (product name + 1-based page range), which triggers the `additional_sds_documents_detected` warning. Callers re-fetch them via the `pages` form field — see the README integration flow.

### Error handling

Errors are `AppError` subclasses (`app/core/exceptions.py`) carrying `status_code` + machine-readable `error_type`; routes never need try/except. When adding an error type, also update `ERROR_RESPONSES` in `app/api/v1/extract.py` (OpenAPI docs) and the error table in the README.

### Testing setup

`tests/conftest.py` sets placeholder `ANTHROPIC_API_KEY`/`API_KEY` env vars **before** any `app.*` import (Settings has no defaults for them and `app.main` resolves Settings at import time) — preserve that ordering. Unit tests mock the Claude client via `tests/factories.py`; integration tests are gated by the `requires_live_api` marker (needs `RUN_INTEGRATION=1` plus a non-placeholder key).
