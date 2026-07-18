package jp.co.ajs.afcsds.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import jp.co.ajs.afcsds.TestFixtures;
import org.junit.jupiter.api.Test;

/**
 * Guards for the versioned schema contract (served at GET /v1/sds/schema;
 * downstream systems generate types from it).
 */
class SdsSchemaTest {

    private static JsonNode snapshot() throws Exception {
        try (InputStream in =
                SdsSchemaTest.class.getResourceAsStream("/fixtures/schema_snapshot.json")) {
            return TestFixtures.MAPPER.readTree(in);
        }
    }

    @Test
    void schemaChangeRequiresVersionBump() throws Exception {
        // Any change to the served schema must come with a SCHEMA_VERSION bump
        // in SdsSchema, a README update, and a regenerated
        // src/test/resources/fixtures/schema_snapshot.json.
        JsonNode snapshot = snapshot();

        assertThat(SdsSchema.SCHEMA_NODE)
                .withFailMessage(
                        "sds_json_schema.json changed but the schema_snapshot.json fixture was not "
                                + "regenerated (or vice versa). The schema is a versioned contract: bump "
                                + "SCHEMA_VERSION in SdsSchema, update the README, and regenerate the snapshot.")
                .isEqualTo(snapshot.get("schema"));
        assertThat(SdsSchema.SCHEMA_VERSION).isEqualTo(snapshot.get("schema_version").asText());
    }

    @Test
    void schemaIsStrictEverywhere() {
        // additionalProperties: false on the root and every nested object —
        // required by structured outputs and load-bearing on the fallback
        // path to reject fabricated fields.
        assertThat(SdsSchema.SCHEMA_NODE.get("additionalProperties").asBoolean()).isFalse();

        JsonNode defs = SdsSchema.SCHEMA_NODE.get("$defs");
        assertThat(defs.size()).isGreaterThan(0);
        for (Iterator<Map.Entry<String, JsonNode>> it = defs.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if ("object".equals(entry.getValue().path("type").asText())) {
                assertThat(entry.getValue().get("additionalProperties").asBoolean())
                        .withFailMessage("$defs.%s must forbid additional properties", entry.getKey())
                        .isFalse();
            }
        }
    }

    @Test
    void minimalPayloadValidatesAndFillsDefaults() throws Exception {
        JsonNode payload = TestFixtures.minimalSdsPayload();
        assertThat(SdsSchema.VALIDATOR.validate(payload)).isEmpty();

        SdsDocument doc = SdsSchema.STRICT_MAPPER.treeToValue(payload, SdsDocument.class);
        assertThat(doc.schemaVersion).isEqualTo("2.1");
        assertThat(doc.section4FirstAid.sectionTitleJa).isEqualTo("応急措置");
        assertThat(doc.additionalDocuments).isEmpty();
        assertThat(doc.extractionNotes).isNull();
        assertThat(doc.section8ExposureControls.exposureLimits).isEmpty();
        assertThat(doc.section9PhysicalChemicalProperties.flashPoint).isNull();
        assertThat(doc.section14Transport.unNumber).isNull();
        assertThat(doc.section15Regulatory.regulations).isEmpty();
    }

    @Test
    void unknownFieldIsRejectedByTheSchema() {
        var payload = TestFixtures.minimalSdsPayload();
        payload.put("unexpected_field", "should not be allowed");
        assertThat(SdsSchema.VALIDATOR.validate(payload)).isNotEmpty();
    }

    @Test
    void extraFieldInsideSectionIsRejectedByTheSchema() {
        var payload = TestFixtures.minimalSdsPayload();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.get("section_4_first_aid"))
                .put("extra", "nope");
        assertThat(SdsSchema.VALIDATOR.validate(payload)).isNotEmpty();
    }

    @Test
    void maximalPayloadRoundTripsThroughStrictMappingAndValidation() throws Exception {
        // The fully-populated counterpart of the minimal-payload test: the
        // response is mapped with the STRICT_MAPPER (unknown properties are
        // fatal), so a mismatched @JsonProperty in any nested type would make
        // real responses carrying that structure fail as
        // extraction_invalid_response — while every minimal-payload test
        // stays green. This pins the mapping for the structures the minimal
        // payload leaves empty.
        JsonNode payload = TestFixtures.maximalSdsPayload();
        assertThat(SdsSchema.VALIDATOR.validate(payload)).isEmpty();

        SdsDocument doc = SdsSchema.STRICT_MAPPER.treeToValue(payload, SdsDocument.class);

        assertThat(doc.section1ProductAndCompany.manufacturer.companyName)
                .isEqualTo("テスト化学株式会社");
        assertThat(doc.section1ProductAndCompany.supplier.emergencyPhone).isEqualTo("0120-111-111");
        assertThat(doc.section2HazardsIdentification.ghsClassifications.get(0).hazardClass)
                .isEqualTo("引火性液体");
        assertThat(doc.section2HazardsIdentification.ghsClassifications.get(1).category).isNull();
        assertThat(doc.section3Composition.ingredients.get(0).concentration).isEqualTo("60~70%");
        assertThat(doc.section8ExposureControls.exposureLimits.get(0).limitType)
                .isEqualTo("日本産業衛生学会 許容濃度");
        assertThat(doc.section8ExposureControls.protectiveEquipment.skinAndBody)
                .isEqualTo("長袖作業衣");
        assertThat(doc.section9PhysicalChemicalProperties.partitionCoefficient)
                .isEqualTo("log Pow -0.32");
        assertThat(doc.section14Transport.unProperShippingName).isEqualTo("エタノール溶液");
        assertThat(doc.section15Regulatory.regulations.get(0).lawName).isEqualTo("消防法");
        assertThat(doc.additionalDocuments.get(0).endPage).isEqualTo(11);
        assertThat(doc.extractionNotes).isNotNull();

        // Serialization loses nothing: the round-tripped tree is identical
        // to the input (the fixture spells out every field, nulls included)
        // and passes schema validation again.
        JsonNode serialized = TestFixtures.MAPPER.valueToTree(doc);
        assertThat(SdsSchema.VALIDATOR.validate(serialized)).isEmpty();
        assertThat(serialized).isEqualTo(payload);
    }

    @Test
    void serializedDocumentContainsEveryFieldInSnakeCase() throws Exception {
        SdsDocument doc =
                SdsSchema.STRICT_MAPPER.treeToValue(TestFixtures.minimalSdsPayload(), SdsDocument.class);
        JsonNode serialized = TestFixtures.MAPPER.valueToTree(doc);

        // Optional fields omitted from the input are still serialized (as
        // null / [] defaults), matching the original Pydantic behavior.
        assertThat(serialized.get("schema_version").asText()).isEqualTo("2.1");
        assertThat(serialized.get("extraction_notes").isNull()).isTrue();
        assertThat(serialized.get("additional_documents").isArray()).isTrue();
        assertThat(serialized.get("section_1_product_and_company").has("product_name")).isTrue();
        assertThat(serialized.get("section_9_physical_chemical_properties").has("flash_point"))
                .isTrue();

        // The serialized form round-trips through schema validation.
        assertThat(SdsSchema.VALIDATOR.validate(serialized)).isEmpty();
    }
}
