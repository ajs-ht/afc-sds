"""Deterministic domain checks on an already schema-valid SDSDocument.

Pydantic validation guarantees the response *shape*; these checks flag
*content* that is mechanically impossible or off-vocabulary — a strong
signal of an OCR misread or model fabrication — without ever mutating or
rejecting the document. Each violation becomes a `warnings` entry of the
form "<kind>:<offending value>", so callers can route suspect extractions
to human review while still receiving the full result.

Checks are intentionally conservative to avoid false alarms on verbatim
transcription: hazard/precautionary statements are only flagged when they
*start with* something that looks like a GHS code but is malformed —
statements without a leading code are left alone.
"""

import re

from app.schemas.sds import SDSDocument

# GHS pictogram vocabulary (絵表示コード). The prompt restricts output to
# these; anything else is fabricated or misread.
_PICTOGRAM_VOCAB = frozenset(f"GHS{n:02d}" for n in range(1, 10))

# Explicit-absence notations commonly written in Japanese SDS where a value
# would otherwise go (非該当, 非開示, 分類基準に該当しない, ...). The model
# transcribes them verbatim per the prompt, so a CAS/UN field holding one is
# faithful transcription, not a misread — don't warn on it. Matched as a
# substring of the whitespace-stripped value so composed phrases hit too
# (e.g. "データなし", "記載なし" — see tests/test_postvalidation.py). This is
# deliberate even for the short/generic markers ("なし", "不明"): a garbled
# OCR string coincidentally containing one as noise would suppress a
# legitimate warning, but tightening to exact-match would break the composed
# phrases above, which real SDS documents use.
_EXPLICIT_ABSENCE_MARKERS = (
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
    "営業秘密",
)

# Table cells whose whole content is dash-like punctuation (―, -, /, …)
# are the symbolic spelling of the same "no value" convention.
_SYMBOLIC_ABSENCE_RE = re.compile(r"[-‐‑–—―ー−－─=~〜/／・.,、。…]+")

_CAS_RE = re.compile(r"(\d{2,7})-(\d{2})-(\d)")

# 国連番号: 4 digits, optionally prefixed with "UN" ("1230" / "UN1230" / "UN 1230").
_UN_NUMBER_RE = re.compile(r"(?:UN\s?)?\d{4}", re.IGNORECASE)

# A leading run of characters that *looks like* a GHS H/P code (so we know
# the model meant to transcribe one). Anchored at the start of the
# (whitespace-stripped) statement rather than split on whitespace, since SDS
# text commonly has no separator between the code and the following Japanese
# phrase (e.g. "H225引火性の高い液体..."); the character class stops the
# match at the first character — space or Japanese text alike — that can't
# be part of a code.
_CODE_LIKE_RE = re.compile(r"[HP]\d[0-9A-Za-z+]*")

# Valid H codes are H + 3 digits with an optional GHS letter suffix
# (H360FD, H361d, ...); valid P codes are P + 3 digits. Either may be a
# "+"-combined sequence (P301+P310).
_VALID_CODE_SEQ_RE = re.compile(
    r"(?:H\d{3}[A-Za-z]{0,2}|P\d{3})(?:\s*\+\s*(?:H\d{3}[A-Za-z]{0,2}|P\d{3}))*"
)


def collect_domain_warnings(doc: SDSDocument) -> list[str]:
    """Return warning strings for mechanically invalid extracted values."""

    warnings: list[str] = []

    for ingredient in doc.section_3_composition.ingredients:
        cas = (ingredient.cas_number or "").strip()
        if cas and not _is_explicit_absence(cas) and not _is_valid_cas(cas):
            warnings.append(f"invalid_cas_number:{_clip(cas)}")

    hazards = doc.section_2_hazards_identification
    for pictogram in hazards.pictograms:
        if pictogram.strip() not in _PICTOGRAM_VOCAB:
            warnings.append(f"unknown_pictogram:{_clip(pictogram)}")

    for statement in hazards.hazard_statements + hazards.precautionary_statements:
        malformed = _malformed_leading_code(statement)
        if malformed is not None:
            warnings.append(f"invalid_ghs_code:{_clip(malformed)}")

    un_number = (doc.section_14_transport.un_number or "").strip()
    if (
        un_number
        and not _is_explicit_absence(un_number)
        and not _UN_NUMBER_RE.fullmatch(un_number)
    ):
        warnings.append(f"invalid_un_number:{_clip(un_number)}")

    return warnings


def _is_explicit_absence(value: str) -> bool:
    normalized = re.sub(r"\s+", "", value)
    if _SYMBOLIC_ABSENCE_RE.fullmatch(normalized):
        return True
    return any(marker in normalized for marker in _EXPLICIT_ABSENCE_MARKERS)


def _is_valid_cas(cas: str) -> bool:
    """Format plus the CAS mod-10 check digit (rightmost digit before the
    check digit is weighted 1, the next 2, and so on)."""

    match = _CAS_RE.fullmatch(cas)
    if not match:
        return False
    digits = match.group(1) + match.group(2)
    checksum = sum(
        weight * int(digit)
        for weight, digit in enumerate(reversed(digits), start=1)
    )
    return checksum % 10 == int(match.group(3))


def _malformed_leading_code(statement: str) -> str | None:
    """Return the leading token if it looks like an H/P code but isn't valid.

    Statements are transcribed verbatim and may legitimately omit codes
    ("引火性の高い液体及び蒸気"), so only a code-shaped leading token is
    ever judged.
    """

    stripped = statement.strip()
    if not stripped:
        return None
    match = _CODE_LIKE_RE.match(stripped)
    if match is None:
        return None
    token = match.group(0)
    if _VALID_CODE_SEQ_RE.fullmatch(token):
        return None
    return token


def _clip(value: str, limit: int = 50) -> str:
    return value if len(value) <= limit else value[: limit - 1] + "…"
