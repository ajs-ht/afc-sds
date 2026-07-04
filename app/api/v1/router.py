from fastapi import APIRouter

from app.api.v1 import extract, schema

router = APIRouter(prefix="/v1")
router.include_router(extract.router, tags=["extraction"])
router.include_router(schema.router, tags=["schema"])
