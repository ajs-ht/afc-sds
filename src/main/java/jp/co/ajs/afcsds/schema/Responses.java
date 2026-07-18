package jp.co.ajs.afcsds.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Response DTOs for the public API (snake_case JSON, matching the Python schema). */
public final class Responses {

    private Responses() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractionUsage(
            long inputTokens,
            long outputTokens,
            long cacheCreationInputTokens,
            long cacheReadInputTokens) {}

    public record SdsExtractionResponse(
            SdsDocument data, List<String> warnings, String model, ExtractionUsage usage) {}

    public record SdsSchemaResponse(
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("json_schema") JsonNode jsonSchema) {}

    public record ErrorDetail(
            String type, String message, @JsonProperty("request_id") String requestId) {}

    public record ErrorResponse(ErrorDetail error) {}
}
