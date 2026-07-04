from fastapi import APIRouter, Depends

from app.dependencies import verify_api_key
from app.schemas.responses import ErrorResponse, SDSSchemaResponse
from app.schemas.sds import SDS_JSON_SCHEMA, SCHEMA_VERSION

router = APIRouter(dependencies=[Depends(verify_api_key)])

ERROR_RESPONSES = {
    401: {"model": ErrorResponse, "description": "Invalid or missing X-API-Key (unauthorized)"},
}


@router.get("/sds/schema", response_model=SDSSchemaResponse, responses=ERROR_RESPONSES)
async def get_sds_schema() -> SDSSchemaResponse:
    """Return the JSON Schema of the extraction result (`data` field).

    Integrating systems can fetch this at runtime for validation or code
    generation instead of hard-coding the shape; `schema_version` identifies
    the contract revision.
    """
    return SDSSchemaResponse(schema_version=SCHEMA_VERSION, json_schema=SDS_JSON_SCHEMA)
