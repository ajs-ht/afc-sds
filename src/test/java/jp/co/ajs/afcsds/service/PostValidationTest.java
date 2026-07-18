package jp.co.ajs.afcsds.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import jp.co.ajs.afcsds.TestFixtures;
import jp.co.ajs.afcsds.schema.SdsDocument;
import jp.co.ajs.afcsds.schema.SdsSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PostValidationTest {

    private static SdsDocument doc(Map<String, JsonNode> sectionOverrides) {
        ObjectNode payload = TestFixtures.minimalSdsPayload();
        sectionOverrides.forEach(
                (key, value) -> ((ObjectNode) payload.get(key)).setAll((ObjectNode) value));
        try {
            return SdsSchema.STRICT_MAPPER.treeToValue(payload, SdsDocument.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ObjectNode node(String json) {
        try {
            return (ObjectNode) TestFixtures.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SdsDocument docWithCas(String cas) {
        return doc(
                Map.of(
                        "section_3_composition",
                        node("{\"ingredients\": [{\"substance_name\": \"x\", \"cas_number\": \"" + cas + "\"}]}")));
    }

    private static SdsDocument docWithUn(String un) {
        return doc(Map.of("section_14_transport", node("{\"un_number\": \"" + un + "\"}")));
    }

    @Test
    void minimalDocumentProducesNoWarnings() {
        assertThat(PostValidation.collectDomainWarnings(doc(Map.of()))).isEmpty();
    }

    // --- CAS numbers --------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"7732-18-5", "64-17-5", "108-88-3"})
    void validCasNumbersPass(String cas) {
        assertThat(PostValidation.collectDomainWarnings(docWithCas(cas))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"7732-18-4", "64-17", "not-a-cas", "1234567890"})
    void invalidCasNumbersWarn(String cas) {
        assertThat(PostValidation.collectDomainWarnings(docWithCas(cas)))
                .containsExactly("invalid_cas_number:" + cas);
    }

    @Test
    void nullCasNumberIsNotChecked() {
        SdsDocument document =
                doc(
                        Map.of(
                                "section_3_composition",
                                node("{\"ingredients\": [{\"substance_name\": \"混合物\"}]}")));
        assertThat(PostValidation.collectDomainWarnings(document)).isEmpty();
    }

    // --- pictograms ---------------------------------------------------------

    @Test
    void knownPictogramsPass() {
        SdsDocument document =
                doc(
                        Map.of(
                                "section_2_hazards_identification",
                                node("{\"pictograms\": [\"GHS02\", \"GHS07\", \"GHS09\"]}")));
        assertThat(PostValidation.collectDomainWarnings(document)).isEmpty();
    }

    @Test
    void unknownPictogramWarns() {
        SdsDocument document =
                doc(
                        Map.of(
                                "section_2_hazards_identification",
                                node("{\"pictograms\": [\"GHS10\", \"炎\"]}")));
        assertThat(PostValidation.collectDomainWarnings(document))
                .containsExactly("unknown_pictogram:GHS10", "unknown_pictogram:炎");
    }

    // --- H/P codes ----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "H225 引火性の高い液体及び蒸気",
                "H360FD 生殖能又は胎児への悪影響のおそれ",
                "P301+P310 飲み込んだ場合:直ちに医師に連絡すること。",
                "H225引火性の高い液体及び蒸気", // valid code, no separating space
                "引火性の高い液体及び蒸気", // no leading code — never judged
                ""
            })
    void wellformedOrCodelessStatementsPass(String statement) {
        SdsDocument document =
                doc(
                        Map.of(
                                "section_2_hazards_identification",
                                node(
                                        "{\"hazard_statements\": ["
                                                + TestFixtures.MAPPER.valueToTree(statement).toString()
                                                + "]}")));
        assertThat(PostValidation.collectDomainWarnings(document)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "H22 引火性の高い液体, H22",
        "H2250 引火性の高い液体, H2250",
        "P30+P310 飲み込んだ場合, P30+P310",
        "H22引火性の高い液体, H22" // malformed code, no separating space
    })
    void malformedLeadingCodesWarn(String statement, String token) {
        SdsDocument document =
                doc(
                        Map.of(
                                "section_2_hazards_identification",
                                node(
                                        "{\"precautionary_statements\": ["
                                                + TestFixtures.MAPPER.valueToTree(statement).toString()
                                                + "]}")));
        assertThat(PostValidation.collectDomainWarnings(document))
                .containsExactly("invalid_ghs_code:" + token);
    }

    // --- UN numbers ---------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1230", "UN1230", "UN 1230"})
    void validUnNumbersPass(String un) {
        assertThat(PostValidation.collectDomainWarnings(docWithUn(un))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "12345", "UNX123"})
    void invalidUnNumbersWarn(String un) {
        assertThat(PostValidation.collectDomainWarnings(docWithUn(un)))
                .containsExactly("invalid_un_number:" + un);
    }

    @Test
    void nullUnNumberIsNotChecked() {
        SdsDocument document = doc(Map.of("section_14_transport", node("{\"un_number\": null}")));
        assertThat(PostValidation.collectDomainWarnings(document)).isEmpty();
    }

    // --- explicit-absence notations (許容リスト) ------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {"非開示", "非該当", "不明", "企業秘密", "データなし", "記載なし", "なし", "―", "-", "－", "／"})
    void explicitAbsenceCasValuesAreNotFlagged(String value) {
        assertThat(PostValidation.collectDomainWarnings(docWithCas(value))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"非該当", "分類基準に該当しない", "該当なし", "適用外", "対象外", "非該当（国連分類）", "―"})
    void explicitAbsenceUnValuesAreNotFlagged(String value) {
        assertThat(PostValidation.collectDomainWarnings(docWithUn(value))).isEmpty();
    }

    @Test
    void absenceMarkerDoesNotMaskARealInvalidValue() {
        // Values that merely *fail* the format without an absence marker still warn.
        assertThat(PostValidation.collectDomainWarnings(docWithUn("UN12345")))
                .containsExactly("invalid_un_number:UN12345");
    }

    @Test
    void longInvalidValuesAreClippedInWarnings() {
        // Warning entries stay bounded even when the offending value is a
        // long OCR garble: clipped to 49 chars plus an ellipsis.
        String garbled = "x".repeat(60);
        assertThat(PostValidation.collectDomainWarnings(docWithCas(garbled)))
                .containsExactly("invalid_cas_number:" + "x".repeat(49) + "…");
    }

    // --- aggregation --------------------------------------------------------

    @Test
    void multipleViolationsAllReported() {
        SdsDocument document =
                doc(
                        Map.of(
                                "section_2_hazards_identification",
                                node("{\"pictograms\": [\"GHS99\"]}"),
                                "section_3_composition",
                                node("{\"ingredients\": [{\"cas_number\": \"1-11-1\"}]}"),
                                "section_14_transport",
                                node("{\"un_number\": \"UN12\"}")));
        assertThat(PostValidation.collectDomainWarnings(document))
                .contains(
                        "invalid_cas_number:1-11-1",
                        "unknown_pictogram:GHS99",
                        "invalid_un_number:UN12");
    }
}
