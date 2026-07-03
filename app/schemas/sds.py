"""JIS Z 7253 16-section SDS (安全データシート) schema.

Sections 1-3 get dedicated structured fields because downstream systems most
commonly need them as structured data (product/company identification, GHS
hazard classification, and the ingredient/composition table). Sections 4-16
use a generic `SDSSection` wrapper that preserves the section's content
faithfully as organized text rather than summarizing it away — SDS content is
often safety-critical and shouldn't be lossily condensed.
"""

from __future__ import annotations

from typing import Literal, Optional

from app.schemas.common import StrictModel

SCHEMA_VERSION = "1.0"


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
    """Generic faithful-text wrapper used for sections 4 through 16."""

    section_number: int
    section_title_ja: str
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
    section_8_exposure_controls: SDSSection  # ばく露防止及び保護措置
    section_9_physical_chemical_properties: SDSSection  # 物理的及び化学的性質
    section_10_stability_reactivity: SDSSection  # 安定性及び反応性
    section_11_toxicological: SDSSection  # 有害性情報
    section_12_ecological: SDSSection  # 環境影響情報
    section_13_disposal: SDSSection  # 廃棄上の注意
    section_14_transport: SDSSection  # 輸送上の注意
    section_15_regulatory: SDSSection  # 適用法令
    section_16_other: SDSSection  # その他の情報

    # Model-reported gaps: illegible, missing, or ambiguous content. Never a
    # substitute for guessing — Claude is instructed to record gaps here
    # rather than fabricate values.
    extraction_notes: Optional[str] = None


# Computed once at import time and reused on every request.
SDS_JSON_SCHEMA = SDSDocument.model_json_schema()
