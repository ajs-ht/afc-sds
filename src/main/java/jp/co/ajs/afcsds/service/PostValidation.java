package jp.co.ajs.afcsds.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jp.co.ajs.afcsds.schema.SdsDocument;

/**
 * Deterministic domain checks on an already schema-valid {@link SdsDocument}.
 *
 * <p>Schema validation guarantees the response <em>shape</em>; these checks
 * flag <em>content</em> that is mechanically impossible or off-vocabulary — a
 * strong signal of an OCR misread or model fabrication — without ever
 * mutating or rejecting the document. Each violation becomes a
 * {@code warnings} entry of the form {@code "<kind>:<offending value>"}, so
 * callers can route suspect extractions to human review while still
 * receiving the full result.
 *
 * <p>Checks are intentionally conservative to avoid false alarms on verbatim
 * transcription: hazard/precautionary statements are only flagged when they
 * <em>start with</em> something that looks like a GHS code but is malformed —
 * statements without a leading code are left alone.
 */
public final class PostValidation {

    private PostValidation() {}

    // GHS pictogram vocabulary (絵表示コード). The prompt restricts output to
    // these; anything else is fabricated or misread.
    private static final Set<String> PICTOGRAM_VOCAB =
            Set.of("GHS01", "GHS02", "GHS03", "GHS04", "GHS05", "GHS06", "GHS07", "GHS08", "GHS09");

    // Explicit-absence notations commonly written in Japanese SDS where a value
    // would otherwise go (非該当, 非開示, 分類基準に該当しない, ...). The model
    // transcribes them verbatim per the prompt, so a CAS/UN field holding one is
    // faithful transcription, not a misread — don't warn on it. Matched as a
    // substring of the whitespace-stripped value so composed phrases hit too
    // (e.g. "データなし", "記載なし"). This is deliberate even for the
    // short/generic markers ("なし", "不明"): a garbled OCR string
    // coincidentally containing one as noise would suppress a legitimate
    // warning, but tightening to exact-match would break the composed phrases
    // above, which real SDS documents use.
    private static final List<String> EXPLICIT_ABSENCE_MARKERS =
            List.of(
                    "非該当",
                    "該当しない",
                    "該当なし",
                    "該当せず",
                    "非開示",
                    "適用外",
                    "対象外",
                    "なし",
                    "不明",
                    "企業秘密",
                    "営業秘密");

    // Table cells whose whole content is dash-like punctuation (―, -, /, …)
    // are the symbolic spelling of the same "no value" convention.
    private static final Pattern SYMBOLIC_ABSENCE =
            Pattern.compile("[-‐‑–—―ー−－─=~〜/／・.,、。…]+");

    private static final Pattern CAS = Pattern.compile("(\\d{2,7})-(\\d{2})-(\\d)");

    // 国連番号: 4 digits, optionally prefixed with "UN" ("1230" / "UN1230" / "UN 1230").
    private static final Pattern UN_NUMBER =
            Pattern.compile("(?:UN\\s?)?\\d{4}", Pattern.CASE_INSENSITIVE);

    // A leading run of characters that *looks like* a GHS H/P code (so we know
    // the model meant to transcribe one). Anchored at the start of the
    // (whitespace-stripped) statement rather than split on whitespace, since
    // SDS text commonly has no separator between the code and the following
    // Japanese phrase (e.g. "H225引火性の高い液体..."); the character class
    // stops the match at the first character — space or Japanese text alike —
    // that can't be part of a code.
    private static final Pattern CODE_LIKE = Pattern.compile("[HP]\\d[0-9A-Za-z+]*");

    // Valid H codes are H + 3 digits with an optional GHS letter suffix
    // (H360FD, H361d, ...); valid P codes are P + 3 digits. Either may be a
    // "+"-combined sequence (P301+P310).
    private static final Pattern VALID_CODE_SEQ =
            Pattern.compile(
                    "(?:H\\d{3}[A-Za-z]{0,2}|P\\d{3})(?:\\s*\\+\\s*(?:H\\d{3}[A-Za-z]{0,2}|P\\d{3}))*");

    // Unicode-aware whitespace (Python's \s matches 全角スペース etc.).
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Returns warning strings for mechanically invalid extracted values. */
    public static List<String> collectDomainWarnings(SdsDocument doc) {
        List<String> warnings = new ArrayList<>();

        for (SdsDocument.Ingredient ingredient : doc.section3Composition.ingredients) {
            String cas = ingredient.casNumber == null ? "" : ingredient.casNumber.strip();
            if (!cas.isEmpty() && !isExplicitAbsence(cas) && !isValidCas(cas)) {
                warnings.add("invalid_cas_number:" + clip(cas));
            }
        }

        SdsDocument.HazardsSummary hazards = doc.section2HazardsIdentification;
        for (String pictogram : hazards.pictograms) {
            if (!PICTOGRAM_VOCAB.contains(pictogram.strip())) {
                warnings.add("unknown_pictogram:" + clip(pictogram));
            }
        }

        Stream.concat(hazards.hazardStatements.stream(), hazards.precautionaryStatements.stream())
                .forEach(
                        statement -> {
                            String malformed = malformedLeadingCode(statement);
                            if (malformed != null) {
                                warnings.add("invalid_ghs_code:" + clip(malformed));
                            }
                        });

        String unNumber =
                doc.section14Transport.unNumber == null ? "" : doc.section14Transport.unNumber.strip();
        if (!unNumber.isEmpty()
                && !isExplicitAbsence(unNumber)
                && !UN_NUMBER.matcher(unNumber).matches()) {
            warnings.add("invalid_un_number:" + clip(unNumber));
        }

        return warnings;
    }

    static boolean isExplicitAbsence(String value) {
        String normalized = WHITESPACE.matcher(value).replaceAll("");
        if (SYMBOLIC_ABSENCE.matcher(normalized).matches()) {
            return true;
        }
        return EXPLICIT_ABSENCE_MARKERS.stream().anyMatch(normalized::contains);
    }

    /**
     * Format plus the CAS mod-10 check digit (rightmost digit before the check
     * digit is weighted 1, the next 2, and so on).
     */
    static boolean isValidCas(String cas) {
        Matcher match = CAS.matcher(cas);
        if (!match.matches()) {
            return false;
        }
        String digits = match.group(1) + match.group(2);
        int checksum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int weight = i + 1;
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            checksum += weight * digit;
        }
        return checksum % 10 == (match.group(3).charAt(0) - '0');
    }

    /**
     * Returns the leading token if it looks like an H/P code but isn't valid.
     *
     * <p>Statements are transcribed verbatim and may legitimately omit codes
     * ("引火性の高い液体及び蒸気"), so only a code-shaped leading token is ever
     * judged.
     */
    static String malformedLeadingCode(String statement) {
        String stripped = statement.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        Matcher match = CODE_LIKE.matcher(stripped);
        if (!match.lookingAt()) {
            return null;
        }
        String token = match.group();
        if (VALID_CODE_SEQ.matcher(token).matches()) {
            return null;
        }
        return token;
    }

    private static String clip(String value) {
        return value.length() <= 50 ? value : value.substring(0, 49) + "…";
    }
}
