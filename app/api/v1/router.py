from fastapi import APIRouter

from app.api.v1 import extract

router = APIRouter(prefix="/v1")
router.include_router(extract.router, tags=["extraction"])
