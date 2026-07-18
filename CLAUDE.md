# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

API-only Spring Boot application (no UI/CLI) that performs AI-OCR on SDS documents (安全データシート / Safety Data Sheets, JIS Z 7253) using the Claude API and returns structured JSON following the standard 16-section JIS Z 7253 layout. The domain, prompts, and README are in Japanese; keep that convention when editing them.

## Commands

```bash
mvn test                       # unit tests + JaCoCo coverage gate (no real API key needed; the live-API test auto-disables)
mvn test -Dtest=FileValidationTest                     # run one test class
mvn test -Dtest='ExtractionServiceTest#successReturnsResultWithoutWarnings'   # run one test

# Live integration test (calls the real Anthropic API)
RUN_INTEGRATION=1 ANTHROPIC_API_KEY=sk-ant-... mvn test -Dtest=LiveExtractionTest

# Run the server
docker compose up --build      # needs .env (copy from .env.example: ANTHROPIC_API_KEY + API_KEY)
ANTHROPIC_API_KEY=... API_KEY=... mvn spring-boot:run   # local, without Docker (port 8000)
```

There is no linter or formatter configured. Java 21 + Maven; Spring Boot with the official Anthropic Java SDK.

## Architecture

Request flow for `POST /v1/sds/extract` (packages under `src/main/java/jp/co/ajs/afcsds/`):

1. `web/RequestIdFilter.java` — assigns a `request_id` (returned as `X-Request-ID`, threaded through all logs and error bodies); `web/GlobalExceptionHandler.java` renders every error as `{"error": {"type", "message", "request_id"}}`.
2. `web/ApiKeyInterceptor.java` — `X-API-Key` shared-secret auth (constant-time compare), applied to all `/v1/**` routes (`config/WebConfig.java`).
3. `validation/FileValidation.java` — size/MIME/PDF-page-count checks (Content-Length pre-check before buffering, then re-check after read; PDFBox). Also hosts `slicePdfPages` backing the optional `pages` form field ("6" / "6-11", 1-based inclusive) used to re-extract the further documents of a multi-SDS PDF.
4. `service/ExtractionService.java` + `service/AnthropicClaudeGateway.java` — sends the file to Claude (base64 document/image block; no sampling params — they 400 on Opus 4.7+) and strictly validates the response against the SDS JSON Schema (networknt) before mapping it to `SdsDocument`; a validation failure not caused by max_tokens truncation is retried once (`retried_invalid_response` warning, usage summed across both calls). Concurrency is bounded by a semaphore sized from `MAX_CONCURRENT_EXTRACTIONS` (default 8): when all slots are busy the request is rejected immediately with `server_busy` (503 + `Retry-After`) rather than queued, since each extraction holds the file in memory across a minutes-long streaming call. Transient API failures (429/5xx/connection) are retried inside the SDK with backoff (`ANTHROPIC_MAX_RETRIES`, default 2). **Always streams** (`createStreaming` + `MessageAccumulator`) so long extractions never trip the SDK's non-streaming timeout. The gateway is behind the `ClaudeGateway` interface so tests can fake it. The model itself is a deployment-wide setting (`AppSettings.modelId` / `MODEL_ID` env var, default `claude-opus-4-8`) with no model-name branching in the request path, so switching models (e.g. to `claude-sonnet-5` for lower cost) needs no code change — but re-check the Opus-4.7+ assumptions above (sampling params, and the Structured Outputs grammar-size limit noted in `service/Prompts.java`) against the new model before relying on them.
5. `service/PostValidation.java` — deterministic domain checks on the validated document (CAS check digit, GHS01–09 pictogram vocabulary, leading H/P-code format, UN-number format); violations become `warnings` entries, never rejections. Explicit-absence notations common in Japanese SDS (非該当, 非開示, 適用外, ...) are allowlisted — a CAS/UN field holding one is faithful transcription, not a misread. New warning kinds also go in the README warnings table.
6. `schema/SdsSchema.java` + `schema/SdsDocument.java` — the 16-section output contract. The JSON Schema lives in `src/main/resources/sds_json_schema.json` (draft 2020-12, generated from the original Pydantic model) and is loaded/compiled once at class-load time; `SdsDocument` is the Jackson mapping of the same shape and fills defaults for omitted optional fields so responses always contain every key.

### Output enforcement: two-tier with automatic fallback

This is the most nuanced part of the codebase — read the docs in `service/Prompts.java` and `service/ExtractionService.java` before touching it.

- **Default (operating mode)**: the JSON schema is embedded in the system prompt (`SYSTEM_PROMPT_WITH_SCHEMA`) and the response is strictly validated against the schema afterwards (networknt + strict Jackson mapping — the Java counterpart of Pydantic validation).
- **Structured outputs** (`output_config.format`, opt-in via `USE_STRUCTURED_OUTPUTS=true`) currently **cannot host the SDS schema**: the API's compiled-grammar limit rejects it no matter how it is slimmed (verified against the live API 2026-07 — even a flat object with 20 optional fields fails). When enabled, a grammar-size 400 flips the `grammarTooLarge` flag on the (singleton) `ExtractionService` instance, retries with the prompt-embedded schema, and appends the `structured_outputs_unavailable` warning. Unit tests get a clean flag by constructing a fresh service; the Spring-context test (`ExtractStructuredOutputsTest`) uses `@DirtiesContext` per method instead.
- Both system-prompt variants are byte-stable (static final constants) and sent with `cache_control: {"type": "ephemeral"}` for prompt caching — don't introduce per-request variation into them.

### Schema is a versioned contract

The schema resource is served verbatim at `GET /v1/sds/schema` with `SCHEMA_VERSION` (`schema/SdsSchema.java`); downstream systems generate types from it. Any change to the output shape requires editing `src/main/resources/sds_json_schema.json` **and** `SdsDocument` together, bumping `SCHEMA_VERSION`, updating the README, and regenerating the snapshot copy in `src/test/resources/fixtures/schema_snapshot.json` (`SdsSchemaTest` fails otherwise). Conventions baked into the schema:

- Every object carries `additionalProperties: false` (the original `StrictModel` convention) — required by structured outputs and load-bearing on the fallback path to reject fabricated fields.
- **All measured values are strings**, never numbers, so ranges/units/qualifiers survive verbatim ("10~20%", "約35℃(密閉式)").
- Sections 1–3, 8, 9, 14, 15 have dedicated structured fields; the rest use the generic `SdsSection`. Every section keeps a faithful-text `content_markdown` / `raw_text` field — structured fields are a projection, not a replacement, because SDS content is safety-critical and must not be lossily condensed.
- Multi-SDS files: extraction always targets the **first** SDS; the model reports the rest in `additional_documents` (product name + 1-based page range), which triggers the `additional_sds_documents_detected` warning. Callers re-fetch them via the `pages` form field — see the README integration flow.
- JSON field names are snake_case; the section fields (`section_1_...`) need explicit `@JsonProperty` because Jackson's SnakeCase strategy would drop the underscore before the digit.

### Error handling

Errors are `AppException` subclasses (`core/AppExceptions.java`) carrying `statusCode` + machine-readable `errorType`; controllers never need try/catch (`web/GlobalExceptionHandler.java` renders them). The Anthropic SDK's exceptions are translated to `service/ClaudeApiException` kinds in the gateway and mapped to `AppException`s in `ExtractionService`. When adding an error type, also update the error table in the README.

### Testing setup

Unit tests fake the Claude client via the `ClaudeGateway` interface (`ExtractionServiceTest.FakeGateway`, `@MockitoBean` in the MockMvc tests). `@SpringBootTest` classes supply the required `afc-sds.anthropic-api-key` / `afc-sds.api-key` properties inline (AppSettings has no defaults for them — startup fails if unset, mirroring the original behavior). The live-API test (`integration/LiveExtractionTest`) is gated by `RUN_INTEGRATION=1` plus a real `ANTHROPIC_API_KEY`. CI runs `mvn -B test`, which includes a JaCoCo line-coverage gate (90%, excluding the SDK adapter and the bootstrap class).
