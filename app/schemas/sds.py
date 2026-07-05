"""JIS Z 7253 16-section SDS (安全データシート) schema.

Sections 1-3 and 8/9/14/15 get dedicated structured fields because downstream
systems most commonly need them as structured data (product/company
identification, GHS hazard classification, the ingredient/composition table,
exposure limits & PPE, physical/chemical properties, transport identifiers,
and applicable regulations). The remaining sections use a generic
`SDSSection` wrapper. Every section keeps a faithful-text field
(`content_markdown`) that preserves the section's full content as organized
text rather than summarizing it away — SDS content is often safety-critical
and shouldn't be lossily condensed; the structured fields are a projection,
not a replacement.

All measured values (temperatures, concentrations, limits, UN numbers) are
strings so ranges, units, and qualifiers survive verbatim ("10~20%",
"約35℃(密閉式)", "<-20℃"). This mirrors the long-standing rule for
`Ingredient.concentration`.
"""

from __future__ import annotations

from typing import Literal, Optional

from app.schemas.common import StrictModel

SCHEMA_VERSION = "2.0"


class ManufacturerContact(StrictModel):
    company_name: Optional[str] = None
    address: Optional[str] = None
    phone: Optional[str] = None
    fax: Optional[str] = None
    emergency_phone: Optional[str] = None
    email: Optional[str] = None


class ProductIdentification(StrictModel):
    """Section 1: 化学品及び会社情報 (Product and company identification)."""

    product_name: Optional[str] = None
    product_code: Optional[str] = None
    recommended_use: Optional[str] = None
    manufacturer: Optional[ManufacturerContact] = None
    supplier: Optional[ManufacturerContact] = None
    raw_text: Optional[str] = None


class GHSClassification(StrictModel):
    hazard_class: str
    category: Optional[str] = None


class HazardsSummary(StrictModel):
    """Section 2: 危険有害性の要約 (Hazards identification)."""

    ghs_classifications: list[GHSClassification] = []
    signal_word: Optional[str] = None
    hazard_statements: list[str] = []
    precautionary_statements: list[str] = []
    pictograms: list[str] = []
    raw_text: Optional[str] = None


class Ingredient(StrictModel):
    substance_name: Optional[str] = None
    cas_number: Optional[str] = None
    # Kept as a string (not a float) so ranges like "10~20%" survive intact.
    concentration: Optional[str] = None


class CompositionInfo(StrictModel):
    """Section 3: 組成及び成分情報 (Composition/information on ingredients)."""

    mixture_or_substance: Optional[Literal["substance", "mixture"]] = None
    ingredients: list[Ingredient] = []
    raw_text: Optional[str] = None


class SDSSection(StrictModel):
    """Generic faithful-text wrapper for sections without dedicated fields."""

    section_number: int
    section_title_ja: str
    content_markdown: str


class ExposureLimit(StrictModel):
    substance_name: Optional[str] = None
    # 管理濃度 / 日本産業衛生学会 許容濃度 / ACGIH TLV-TWA など、原文の区分名のまま。
    limit_type: Optional[str] = None
    value: Optional[str] = None


class ProtectiveEquipment(StrictModel):
    respiratory: Optional[str] = None  # 呼吸用保護具
    hands: Optional[str] = None  # 保護手袋
    eyes: Optional[str] = None  # 保護眼鏡
    skin_and_body: Optional[str] = None  # 保護衣


class ExposureControls(StrictModel):
    """Section 8: ばく露防止及び保護措置 (Exposure controls/personal protection)."""

    section_number: int
    section_title_ja: str
    exposure_limits: list[ExposureLimit] = []
    engineering_controls: Optional[str] = None  # 設備対策
    protective_equipment: Optional[ProtectiveEquipment] = None
    content_markdown: str


class PhysicalChemicalProperties(StrictModel):
    """Section 9: 物理的及び化学的性質 (Physical and chemical properties)."""

    section_number: int
    section_title_ja: str
    physical_state: Optional[str] = None  # 物理的状態(固体/液体/気体)
    appearance: Optional[str] = None  # 外観(形状・色など)
    odor: Optional[str] = None  # 臭い
    ph: Optional[str] = None
    melting_point: Optional[str] = None  # 融点・凝固点
    boiling_point: Optional[str] = None  # 沸点、初留点及び沸騰範囲
    flash_point: Optional[str] = None  # 引火点
    autoignition_temperature: Optional[str] = None  # 自然発火温度
    decomposition_temperature: Optional[str] = None  # 分解温度
    explosion_range_lower: Optional[str] = None  # 爆発範囲(燃焼範囲)下限
    explosion_range_upper: Optional[str] = None  # 爆発範囲(燃焼範囲)上限
    vapor_pressure: Optional[str] = None  # 蒸気圧
    vapor_density: Optional[str] = None  # 蒸気密度
    density: Optional[str] = None  # 密度・比重
    solubility_water: Optional[str] = None  # 水への溶解度
    solubility_other: Optional[str] = None  # その他の溶媒への溶解度
    partition_coefficient: Optional[str] = None  # n-オクタノール/水分配係数
    viscosity: Optional[str] = None  # 粘度(粘性率)
    content_markdown: str


class TransportInfo(StrictModel):
    """Section 14: 輸送上の注意 (Transport information)."""

    section_number: int
    section_title_ja: str
    un_number: Optional[str] = None  # 国連番号
    un_proper_shipping_name: Optional[str] = None  # 品名(国連輸送名)
    transport_hazard_class: Optional[str] = None  # 輸送における危険有害性クラス
    packing_group: Optional[str] = None  # 容器等級
    marine_pollutant: Optional[str] = None  # 海洋汚染物質(該当/非該当の原文)
    emergency_response_guide: Optional[str] = None  # 緊急時応急措置指針番号
    content_markdown: str


class ApplicableRegulation(StrictModel):
    # 法令名は原文のまま (例: 消防法、毒物及び劇物取締法、労働安全衛生法)。
    law_name: str
    classification: Optional[str] = None  # 該当区分・号別など原文のまま


class RegulatoryInfo(StrictModel):
    """Section 15: 適用法令 (Regulatory information)."""

    section_number: int
    section_title_ja: str
    regulations: list[ApplicableRegulation] = []
    content_markdown: str


class SDSDocument(StrictModel):
    schema_version: str = SCHEMA_VERSION

    section_1_product_and_company: ProductIdentification
    section_2_hazards_identification: HazardsSummary
    section_3_composition: CompositionInfo

    section_4_first_aid: SDSSection  # 応急措置
    section_5_firefighting: SDSSection  # 火災時の措置
    section_6_accidental_release: SDSSection  # 漏出時の措置
    section_7_handling_storage: SDSSection  # 取扱い及び保管上の注意
    section_8_exposure_controls: ExposureControls  # ばく露防止及び保護措置
    section_9_physical_chemical_properties: PhysicalChemicalProperties  # 物理的及び化学的性質
    section_10_stability_reactivity: SDSSection  # 安定性及び反応性
    section_11_toxicological: SDSSection  # 有害性情報
    section_12_ecological: SDSSection  # 環境影響情報
    section_13_disposal: SDSSection  # 廃棄上の注意
    section_14_transport: TransportInfo  # 輸送上の注意
    section_15_regulatory: RegulatoryInfo  # 適用法令
    section_16_other: SDSSection  # その他の情報

    # Model-reported gaps: illegible, missing, or ambiguous content. Never a
    # substitute for guessing — Claude is instructed to record gaps here
    # rather than fabricate values.
    extraction_notes: Optional[str] = None


# Computed once at import time and reused on every request.
SDS_JSON_SCHEMA = SDSDocument.model_json_schema()
