package jp.co.ajs.afcsds.service;

import static org.assertj.core.api.Assertions.assertThat;

import jp.co.ajs.afcsds.schema.SdsSchema;
import org.junit.jupiter.api.Test;

/**
 * Guards for the prompt-caching invariants (see {@link Prompts}).
 *
 * <p>Both system-prompt variants are sent with
 * {@code cache_control: {"type": "ephemeral"}}; prompt caching only works if
 * the prompt bytes are identical across requests, so any per-request
 * variation (request_id, timestamps, filenames, ...) silently multiplies API
 * cost. In Java the variants are static final constants — inherently
 * byte-stable — so these tests pin down their composition instead.
 */
class PromptsTest {

    @Test
    void withSchemaPromptIsBasePlusSchemaAppendix() {
        assertThat(Prompts.SYSTEM_PROMPT_WITH_SCHEMA).startsWith(Prompts.SYSTEM_PROMPT_BASE);
        String appendix =
                Prompts.SYSTEM_PROMPT_WITH_SCHEMA.substring(Prompts.SYSTEM_PROMPT_BASE.length());
        // The schema is embedded verbatim (non-ASCII preserved), so the
        // fallback path enforces exactly the same contract as structured
        // outputs would.
        assertThat(appendix).contains(SdsSchema.SCHEMA_JSON);
        assertThat(appendix).contains("section_1_product_and_company");
    }

    @Test
    void basePromptDoesNotEmbedTheSchema() {
        assertThat(Prompts.SYSTEM_PROMPT_BASE).doesNotContain("section_1_product_and_company");
    }

    @Test
    void promptsHaveNoUnresolvedPlaceholders() {
        for (String text :
                new String[] {
                    Prompts.SYSTEM_PROMPT_BASE, Prompts.SYSTEM_PROMPT_WITH_SCHEMA, Prompts.USER_INSTRUCTION
                }) {
            assertThat(text).doesNotContain("{schema_json}");
        }
    }
}
