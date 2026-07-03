from app.schemas.common import StrictModel
from app.schemas.sds import SDSDocument


class ExtractionUsage(StrictModel):
    input_tokens: int
    output_tokens: int
    cache_creation_input_tokens: int = 0
    cache_read_input_tokens: int = 0


class SDSExtractionResponse(StrictModel):
    data: SDSDocument
    warnings: list[str] = []
    model: str
    usage: ExtractionUsage


class ErrorDetail(StrictModel):
    type: str
    message: str


class ErrorResponse(StrictModel):
    error: ErrorDetail
