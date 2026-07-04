"""Shared test data/fakes used across multiple test modules."""

from types import SimpleNamespace


def _minimal_section(number: int, title: str) -> dict:
    return {
        "section_number": number,
        "section_title_ja": title,
        "content_markdown": "",
    }


def minimal_sds_payload() -> dict:
    return {
        "section_1_product_and_company": {},
        "section_2_hazards_identification": {},
        "section_3_composition": {},
        "section_4_first_aid": _minimal_section(4, "応急措置"),
        "section_5_firefighting": _minimal_section(5, "火災時の措置"),
        "section_6_accidental_release": _minimal_section(6, "漏出時の措置"),
        "section_7_handling_storage": _minimal_section(7, "取扱い及び保管上の注意"),
        "section_8_exposure_controls": _minimal_section(8, "ばく露防止及び保護措置"),
        "section_9_physical_chemical_properties": _minimal_section(9, "物理的及び化学的性質"),
        "section_10_stability_reactivity": _minimal_section(10, "安定性及び反応性"),
        "section_11_toxicological": _minimal_section(11, "有害性情報"),
        "section_12_ecological": _minimal_section(12, "環境影響情報"),
        "section_13_disposal": _minimal_section(13, "廃棄上の注意"),
        "section_14_transport": _minimal_section(14, "輸送上の注意"),
        "section_15_regulatory": _minimal_section(15, "適用法令"),
        "section_16_other": _minimal_section(16, "その他の情報"),
    }


def fake_message(*, text: str, stop_reason: str, stop_details=None):
    return SimpleNamespace(
        content=[SimpleNamespace(type="text", text=text)],
        stop_reason=stop_reason,
        stop_details=stop_details,
        model="claude-opus-4-8",
        usage=SimpleNamespace(
            input_tokens=1000,
            output_tokens=500,
            cache_creation_input_tokens=200,
            cache_read_input_tokens=0,
        ),
    )


class FakeStreamContext:
    """Stands in for the async context manager returned by
    AsyncAnthropic().messages.stream(...)."""

    def __init__(self, message):
        self._message = message

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc_info):
        return False

    async def get_final_message(self):
        return self._message
