package jp.co.ajs.afcsds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.service.ClaudeMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/** Shared test data/fakes used across multiple test classes. */
public final class TestFixtures {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private TestFixtures() {}

    public static AppSettings settings() {
        return new AppSettings("k", "claude-opus-4-8", "s", 32, 50, 24000, false, "INFO", "text");
    }

    /** Settings with structured outputs opted in (off by default). */
    public static AppSettings structuredOutputsSettings() {
        return new AppSettings("k", "claude-opus-4-8", "s", 32, 50, 24000, true, "INFO", "text");
    }

    private static ObjectNode minimalSection(int number, String title) {
        ObjectNode section = MAPPER.createObjectNode();
        section.put("section_number", number);
        section.put("section_title_ja", title);
        section.put("content_markdown", "");
        return section;
    }

    public static ObjectNode minimalSdsPayload() {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("section_1_product_and_company", MAPPER.createObjectNode());
        root.set("section_2_hazards_identification", MAPPER.createObjectNode());
        root.set("section_3_composition", MAPPER.createObjectNode());
        root.set("section_4_first_aid", minimalSection(4, "応急措置"));
        root.set("section_5_firefighting", minimalSection(5, "火災時の措置"));
        root.set("section_6_accidental_release", minimalSection(6, "漏出時の措置"));
        root.set("section_7_handling_storage", minimalSection(7, "取扱い及び保管上の注意"));
        root.set("section_8_exposure_controls", minimalSection(8, "ばく露防止及び保護措置"));
        root.set("section_9_physical_chemical_properties", minimalSection(9, "物理的及び化学的性質"));
        root.set("section_10_stability_reactivity", minimalSection(10, "安定性及び反応性"));
        root.set("section_11_toxicological", minimalSection(11, "有害性情報"));
        root.set("section_12_ecological", minimalSection(12, "環境影響情報"));
        root.set("section_13_disposal", minimalSection(13, "廃棄上の注意"));
        root.set("section_14_transport", minimalSection(14, "輸送上の注意"));
        root.set("section_15_regulatory", minimalSection(15, "適用法令"));
        root.set("section_16_other", minimalSection(16, "その他の情報"));
        return root;
    }

    public static ClaudeMessage fakeMessage(String text, String stopReason) {
        return fakeMessage(text, stopReason, null);
    }

    public static ClaudeMessage fakeMessage(String text, String stopReason, String refusalCategory) {
        return new ClaudeMessage(
                "claude-opus-4-8",
                stopReason,
                refusalCategory,
                text,
                new ClaudeMessage.Usage(1000, 500, 200, 0));
    }

    public static byte[] pdfWithPages(int count) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < count; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            document.save(buffer);
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static int pdfPageCount(byte[] content) {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(content)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] samplePdfBytes() {
        try (InputStream in = TestFixtures.class.getResourceAsStream("/fixtures/sample_sds.pdf")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
