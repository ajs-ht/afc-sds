package jp.co.ajs.afcsds.api;

import jp.co.ajs.afcsds.schema.Responses.SdsSchemaResponse;
import jp.co.ajs.afcsds.schema.SdsSchema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class SchemaController {

    /**
     * Return the JSON Schema of the extraction result ({@code data} field).
     *
     * <p>Integrating systems can fetch this at runtime for validation or code
     * generation instead of hard-coding the shape; {@code schema_version}
     * identifies the contract revision.
     */
    @GetMapping("/sds/schema")
    public SdsSchemaResponse getSdsSchema() {
        return new SdsSchemaResponse(SdsSchema.SCHEMA_VERSION, SdsSchema.SCHEMA_NODE);
    }
}
