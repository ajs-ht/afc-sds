package jp.co.ajs.afcsds.schema;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The SDS output JSON Schema — a versioned public contract.
 *
 * <p>The schema lives in {@code src/main/resources/sds_json_schema.json}
 * (draft 2020-12, generated from the original Pydantic model and kept
 * byte-for-byte in sync with the {@code schema_snapshot.json} test fixture).
 * It is served verbatim at {@code GET /v1/sds/schema}; downstream systems
 * generate types from it. Any change to the output shape requires bumping
 * {@link #SCHEMA_VERSION} and updating the README.
 *
 * <p>Every object in the schema carries {@code additionalProperties: false}
 * (the Pydantic {@code StrictModel} convention) — required by Claude's
 * structured outputs, and equally load-bearing on the prompt-embedded
 * fallback path, where it lets validation reject fabricated fields.
 *
 * <p>All artifacts here are computed once at class-load time and reused on
 * every request.
 */
public final class SdsSchema {

    public static final String SCHEMA_VERSION = "2.1";

    /** The parsed schema document (insertion order preserved). */
    public static final JsonNode SCHEMA_NODE;

    /**
     * Compact single-line serialization of the schema, embedded into the
     * system prompt. Byte-stable across requests so prompt caching works.
     */
    public static final String SCHEMA_JSON;

    /** Compiled validator used to strictly check Claude's responses. */
    public static final JsonSchema VALIDATOR;

    /**
     * Strict mapper for Claude responses: unknown properties already fail
     * schema validation, this is defense in depth for the tree-to-DTO step.
     */
    public static final ObjectMapper STRICT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    static {
        try (InputStream in = SdsSchema.class.getResourceAsStream("/sds_json_schema.json")) {
            if (in == null) {
                throw new IllegalStateException("sds_json_schema.json resource is missing");
            }
            SCHEMA_NODE = new ObjectMapper().readTree(in);
            SCHEMA_JSON = new ObjectMapper().writeValueAsString(SCHEMA_NODE);
            VALIDATOR =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(SCHEMA_NODE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load sds_json_schema.json", e);
        }
    }

    private SdsSchema() {}
}
