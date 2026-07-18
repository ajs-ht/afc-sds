package jp.co.ajs.afcsds.service;

/** Exposes package-private {@link ExtractionService} test hooks to tests in other packages. */
public final class ExtractionServiceTestSupport {

    private ExtractionServiceTestSupport() {}

    /** See {@link ExtractionService#resetGrammarTooLarge()}: the fallback flag is process-local. */
    public static void resetGrammarTooLarge() {
        ExtractionService.resetGrammarTooLarge();
    }
}
