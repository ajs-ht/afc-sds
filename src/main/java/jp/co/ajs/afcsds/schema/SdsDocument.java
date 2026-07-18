package jp.co.ajs.afcsds.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.ArrayList;
import java.util.List;

/**
 * JIS Z 7253 16-section SDS (安全データシート) document model.
 *
 * <p>This mirrors the JSON Schema in {@code sds_json_schema.json} — the
 * schema (validated with networknt before deserialization) is the strict
 * contract; these classes carry the validated data and fill in defaults for
 * omitted optional fields so the serialized response always contains every
 * key, exactly like the original Pydantic model.
 *
 * <p>Sections 1-3 and 8/9/14/15 get dedicated structured fields because
 * downstream systems most commonly need them as structured data. The
 * remaining sections use the generic {@link SdsSection} wrapper. Every
 * section keeps a faithful-text field ({@code contentMarkdown} /
 * {@code rawText}) that preserves the section's full content — SDS content
 * is safety-critical and must not be lossily condensed; the structured
 * fields are a projection, not a replacement.
 *
 * <p>All measured values (temperatures, concentrations, limits, UN numbers)
 * are strings so ranges, units, and qualifiers survive verbatim ("10~20%",
 * "約35℃(密閉式)", "&lt;-20℃").
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SdsDocument {

    public String schemaVersion = SdsSchema.SCHEMA_VERSION;

    @JsonProperty("section_1_product_and_company")
    public ProductIdentification section1ProductAndCompany;
    @JsonProperty("section_2_hazards_identification")
    public HazardsSummary section2HazardsIdentification;
    @JsonProperty("section_3_composition")
    public CompositionInfo section3Composition;

    @JsonProperty("section_4_first_aid")
    public SdsSection section4FirstAid; // 応急措置
    @JsonProperty("section_5_firefighting")
    public SdsSection section5Firefighting; // 火災時の措置
    @JsonProperty("section_6_accidental_release")
    public SdsSection section6AccidentalRelease; // 漏出時の措置
    @JsonProperty("section_7_handling_storage")
    public SdsSection section7HandlingStorage; // 取扱い及び保管上の注意
    @JsonProperty("section_8_exposure_controls")
    public ExposureControls section8ExposureControls; // ばく露防止及び保護措置
    @JsonProperty("section_9_physical_chemical_properties")
    public PhysicalChemicalProperties section9PhysicalChemicalProperties; // 物理的及び化学的性質
    @JsonProperty("section_10_stability_reactivity")
    public SdsSection section10StabilityReactivity; // 安定性及び反応性
    @JsonProperty("section_11_toxicological")
    public SdsSection section11Toxicological; // 有害性情報
    @JsonProperty("section_12_ecological")
    public SdsSection section12Ecological; // 環境影響情報
    @JsonProperty("section_13_disposal")
    public SdsSection section13Disposal; // 廃棄上の注意
    @JsonProperty("section_14_transport")
    public TransportInfo section14Transport; // 輸送上の注意
    @JsonProperty("section_15_regulatory")
    public RegulatoryInfo section15Regulatory; // 適用法令
    @JsonProperty("section_16_other")
    public SdsSection section16Other; // その他の情報

    /**
     * SDS documents beyond the first found in the same file (multi-SDS PDFs).
     * The first SDS is what the fields above describe; entries here let
     * callers fetch the rest with a {@code pages}-scoped re-request.
     * Non-empty triggers the {@code additional_sds_documents_detected}
     * response warning.
     */
    public List<DetectedDocument> additionalDocuments = new ArrayList<>();

    /**
     * Model-reported gaps: illegible, missing, or ambiguous content. Never a
     * substitute for guessing — Claude is instructed to record gaps here
     * rather than fabricate values.
     */
    public String extractionNotes;

    /**
     * A further SDS the model noticed in the same file but did not extract.
     * Page numbers are 1-based, inclusive, and model-estimated.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class DetectedDocument {
        public String productName;
        public Integer startPage;
        public Integer endPage;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ManufacturerContact {
        public String companyName;
        public String address;
        public String phone;
        public String fax;
        public String emergencyPhone;
        public String email;
    }

    /** Section 1: 化学品及び会社情報 (Product and company identification). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ProductIdentification {
        public String productName;
        public String productCode;
        public String recommendedUse;
        public ManufacturerContact manufacturer;
        public ManufacturerContact supplier;
        public String rawText;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class GhsClassification {
        public String hazardClass;
        public String category;
    }

    /** Section 2: 危険有害性の要約 (Hazards identification). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class HazardsSummary {
        public List<GhsClassification> ghsClassifications = new ArrayList<>();
        public String signalWord;
        public List<String> hazardStatements = new ArrayList<>();
        public List<String> precautionaryStatements = new ArrayList<>();
        public List<String> pictograms = new ArrayList<>();
        public String rawText;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Ingredient {
        public String substanceName;
        public String casNumber;
        // Kept as a string (not a number) so ranges like "10~20%" survive intact.
        public String concentration;
    }

    /** Section 3: 組成及び成分情報 (Composition/information on ingredients). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class CompositionInfo {
        public String mixtureOrSubstance; // "substance" | "mixture" (enforced by the schema)
        public List<Ingredient> ingredients = new ArrayList<>();
        public String rawText;
    }

    /** Generic faithful-text wrapper for sections without dedicated fields. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class SdsSection {
        public int sectionNumber;
        public String sectionTitleJa;
        public String contentMarkdown;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ExposureLimit {
        public String substanceName;
        // 管理濃度 / 日本産業衛生学会 許容濃度 / ACGIH TLV-TWA など、原文の区分名のまま。
        public String limitType;
        public String value;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ProtectiveEquipment {
        public String respiratory; // 呼吸用保護具
        public String hands; // 保護手袋
        public String eyes; // 保護眼鏡
        public String skinAndBody; // 保護衣
    }

    /** Section 8: ばく露防止及び保護措置 (Exposure controls/personal protection). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ExposureControls {
        public int sectionNumber;
        public String sectionTitleJa;
        public List<ExposureLimit> exposureLimits = new ArrayList<>();
        public String engineeringControls; // 設備対策
        public ProtectiveEquipment protectiveEquipment;
        public String contentMarkdown;
    }

    /** Section 9: 物理的及び化学的性質 (Physical and chemical properties). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class PhysicalChemicalProperties {
        public int sectionNumber;
        public String sectionTitleJa;
        public String physicalState; // 物理的状態(固体/液体/気体)
        public String appearance; // 外観(形状・色など)
        public String odor; // 臭い
        public String ph;
        public String meltingPoint; // 融点・凝固点
        public String boilingPoint; // 沸点、初留点及び沸騰範囲
        public String flashPoint; // 引火点
        public String autoignitionTemperature; // 自然発火温度
        public String decompositionTemperature; // 分解温度
        public String explosionRangeLower; // 爆発範囲(燃焼範囲)下限
        public String explosionRangeUpper; // 爆発範囲(燃焼範囲)上限
        public String vaporPressure; // 蒸気圧
        public String vaporDensity; // 蒸気密度
        public String density; // 密度・比重
        public String solubilityWater; // 水への溶解度
        public String solubilityOther; // その他の溶媒への溶解度
        public String partitionCoefficient; // n-オクタノール/水分配係数
        public String viscosity; // 粘度(粘性率)
        public String contentMarkdown;
    }

    /** Section 14: 輸送上の注意 (Transport information). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class TransportInfo {
        public int sectionNumber;
        public String sectionTitleJa;
        public String unNumber; // 国連番号
        public String unProperShippingName; // 品名(国連輸送名)
        public String transportHazardClass; // 輸送における危険有害性クラス
        public String packingGroup; // 容器等級
        public String marinePollutant; // 海洋汚染物質(該当/非該当の原文)
        public String emergencyResponseGuide; // 緊急時応急措置指針番号
        public String contentMarkdown;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ApplicableRegulation {
        // 法令名は原文のまま (例: 消防法、毒物及び劇物取締法、労働安全衛生法)。
        public String lawName;
        public String classification; // 該当区分・号別など原文のまま
    }

    /** Section 15: 適用法令 (Regulatory information). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class RegulatoryInfo {
        public int sectionNumber;
        public String sectionTitleJa;
        public List<ApplicableRegulation> regulations = new ArrayList<>();
        public String contentMarkdown;
    }
}
