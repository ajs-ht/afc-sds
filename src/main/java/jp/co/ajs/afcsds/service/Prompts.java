package jp.co.ajs.afcsds.service;

import jp.co.ajs.afcsds.schema.SdsSchema;

/**
 * Prompt text for SDS extraction.
 *
 * <p>Two system-prompt variants exist for the two output-enforcement modes
 * (see {@link ExtractionService}):
 *
 * <ul>
 *   <li>{@link #SYSTEM_PROMPT_BASE} — extraction rules only. Used when
 *       Claude's structured outputs ({@code output_config.format}) enforce
 *       the JSON shape at the API level, so embedding the schema in the
 *       prompt would only waste tokens.
 *   <li>{@link #SYSTEM_PROMPT_WITH_SCHEMA} — BASE plus the JSON schema
 *       embedded as text. Used when structured outputs are disabled via
 *       settings (the default) or as the automatic fallback when the
 *       compiled structured-outputs grammar exceeds the API's size limit;
 *       the response is then strictly validated against the schema after the
 *       fact.
 * </ul>
 *
 * <p>Why structured outputs are off by default (probed against the live API,
 * 2026-07): the grammar compiler rejects even a flat object with 20
 * <em>optional</em> string fields ("Schema is too complex"), while
 * required-only flat objects pass at 60+ fields. The SDS schema is dominated
 * by optional fields, nested objects, and arrays, and every slimming variant
 * tried (stripping title/default/description, collapsing anyOf-null, making
 * all fields required) still got "The compiled grammar is too large". Until
 * the limit is raised, constrained decoding cannot host this schema — the
 * prompt-embedded fallback is the operating mode.
 *
 * <p>Each variant is byte-stable across requests, so both are sent with
 * {@code cache_control: {"type": "ephemeral"}} and cache independently.
 */
public final class Prompts {

    private Prompts() {}

    public static final String SYSTEM_PROMPT_BASE =
            """
            あなたは化学物質の安全データシート(SDS)をJIS Z 7253の標準16項目構成に沿って
            構造化JSONへ変換する、高精度なAI-OCR/抽出エンジンです。

            # JIS Z 7253 標準16項目
            1. 化学品及び会社情報 (Product and company identification)
            2. 危険有害性の要約 (Hazards identification)
            3. 組成及び成分情報 (Composition/information on ingredients)
            4. 応急措置 (First aid measures)
            5. 火災時の措置 (Firefighting measures)
            6. 漏出時の措置 (Accidental release measures)
            7. 取扱い及び保管上の注意 (Handling and storage)
            8. ばく露防止及び保護措置 (Exposure controls/personal protection)
            9. 物理的及び化学的性質 (Physical and chemical properties)
            10. 安定性及び反応性 (Stability and reactivity)
            11. 有害性情報 (Toxicological information)
            12. 環境影響情報 (Ecological information)
            13. 廃棄上の注意 (Disposal considerations)
            14. 輸送上の注意 (Transport information)
            15. 適用法令 (Regulatory information)
            16. その他の情報 (Other information)

            # 抽出ルール
            - 日本語の内容は翻訳せず、原文のまま逐語的に書き起こしてください。数値、単位、
              CAS番号、GHSのHコード/P コードの文言などは要約せず、原文の表記のまま保持
              してください。
            - SDSの体裁はメーカーごとに異なります(項目の統合、順序の違い、表記ゆれなど)。
              実際のレイアウトに関わらず、内容を上記16項目の固定スキーマへ正しくマッピング
              してください。
            - ある項目が欠落している、または判読できない場合は、該当フィールドを空文字列
              のままにし、"extraction_notes" にその旨を具体的に記録してください。存在しない
              情報を推測・創作してはいけません。
            - ピクトグラムは標準語彙 (GHS01〜GHS09) のみを使用し、それ以外のラベルを創作
              しないでください。
            - 成分の濃度 (concentration) は "10~20%" のような範囲表記をそのまま文字列として
              保持し、数値への変換や丸めを行わないでください。
            - 第8・9・14・15項には構造化フィールドがあります。ばく露限界値(管理濃度・許容
              濃度など区分名は原文のまま)、物性値(融点・引火点・蒸気圧など)、国連番号・
              容器等級、適用法令名と該当区分を、範囲・単位・付帯条件を含む原文表記のまま
              文字列として該当フィールドへ転記してください。記載のないフィールドは null の
              ままにしてください。
            - 構造化フィールドの有無にかかわらず、各項目の "content_markdown" には原文の
              情報を欠落させることなく、読みやすいMarkdown形式のテキストとして整理して
              ください(表はMarkdown表記で構いません)。
            - 1つのファイルに複数のSDS(別製品のSDS)が含まれる場合は、最初のSDSのみを
              抽出対象とし、2件目以降の各SDSについて "additional_documents" に製品名と
              ページ範囲(start_page / end_page、1始まり・両端含む)を記録してください。
              ファイルに含まれるSDSが1件だけの場合、"additional_documents" は空配列の
              ままにしてください。
            - 出力はスキーマに厳密に従った有効なJSONオブジェクトのみとしてください。
              スキーマにないフィールドを追加せず、前置き・説明文・```のようなコードブロック
              記法を一切含めないでください。
            """;

    public static final String SYSTEM_PROMPT_WITH_SCHEMA =
            SYSTEM_PROMPT_BASE + "\n# 出力JSON Schema\n" + SdsSchema.SCHEMA_JSON + "\n";

    public static final String USER_INSTRUCTION =
            "添付のSDS文書を読み取り、JIS Z 7253の16項目構成に沿った構造化JSONとして"
                    + "抽出してください。";

    /**
     * Follow-up user turn for the schema-validation retry: the failed
     * response is replayed as an assistant turn, then this message (with the
     * validation errors interpolated via {@code formatted}) asks for a full
     * corrected re-output. Per-request variation here is fine — only the
     * system prompt must stay byte-stable for prompt caching.
     */
    public static final String CORRECTION_INSTRUCTION_TEMPLATE =
            """
            直前のあなたの出力は、次の検証エラーによりJSON Schema検証に失敗しました:

            %s

            同じSDS文書について、これらのエラーをすべて解消した完全なJSONオブジェクトを
            最初から出力し直してください。修正箇所の差分ではなく文書全体を出力してください。
            スキーマにないフィールドの追加、前置き・説明文・```のようなコードブロック記法は
            引き続き禁止です。
            """;
}
