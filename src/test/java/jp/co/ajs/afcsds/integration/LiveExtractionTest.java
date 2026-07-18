package jp.co.ajs.afcsds.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jp.co.ajs.afcsds.TestFixtures;
import jp.co.ajs.afcsds.config.AppSettings;
import jp.co.ajs.afcsds.schema.Responses.SdsExtractionResponse;
import jp.co.ajs.afcsds.service.AnthropicClaudeGateway;
import jp.co.ajs.afcsds.service.ExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end test against the real Claude API. Skipped by default; to run it:
 *
 * <pre>RUN_INTEGRATION=1 ANTHROPIC_API_KEY=sk-ant-... mvn test -Dtest=LiveExtractionTest</pre>
 *
 * <p>Sends a small, self-authored, non-proprietary Japanese SDS-shaped PDF
 * fixture through the real extraction flow and does a light sanity check on
 * the result plus logs token usage for a cost sanity check.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION", matches = "1")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = "sk-.*")
class LiveExtractionTest {

    @Test
    void liveExtractionReturnsStructuredSds() {
        AppSettings settings =
                new AppSettings(
                        System.getenv("ANTHROPIC_API_KEY"),
                        System.getenv().getOrDefault("MODEL_ID", "claude-opus-4-8"),
                        "unused",
                        32,
                        50,
                        24000,
                        Boolean.parseBoolean(
                                System.getenv().getOrDefault("USE_STRUCTURED_OUTPUTS", "false")),
                        "INFO",
                        "text");
        ExtractionService service =
                new ExtractionService(new AnthropicClaudeGateway(settings), settings);

        SdsExtractionResponse result =
                service.extractSds(TestFixtures.samplePdfBytes(), "application/pdf", "live-test");

        assertThat(result.data().schemaVersion).isEqualTo("2.1");
        assertThat(result.data().section1ProductAndCompany).isNotNull();

        // Trivially true while USE_STRUCTURED_OUTPUTS is off (the default).
        // Its real job is the opt-in retest after Anthropic raises the
        // grammar limits: run with USE_STRUCTURED_OUTPUTS=true — if the
        // schema now compiles there's no warning and this passes; if it
        // still falls back, this fails loudly instead of papering over the
        // silent downgrade.
        assertThat(result.warnings())
                .withFailMessage(
                        "structured outputs grammar was rejected as too large; keep "
                                + "USE_STRUCTURED_OUTPUTS=false until the API limit is raised")
                .doesNotContain("structured_outputs_unavailable");

        System.out.printf(
                "[integration] model=%s input_tokens=%d output_tokens=%d cache_read_input_tokens=%d%n",
                result.model(),
                result.usage().inputTokens(),
                result.usage().outputTokens(),
                result.usage().cacheReadInputTokens());
    }
}
