import os
from pathlib import Path

# Settings requires ANTHROPIC_API_KEY / API_KEY with no defaults, and
# app.main resolves Settings at import time — set test defaults before any
# `app.*` module is imported.
os.environ.setdefault("ANTHROPIC_API_KEY", "test-anthropic-key")
os.environ.setdefault("API_KEY", "test-api-key")

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app

FIXTURES_DIR = Path(__file__).parent / "fixtures"

TEST_API_KEY = get_settings().api_key


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


@pytest.fixture
def auth_headers() -> dict[str, str]:
    return {"X-API-Key": TEST_API_KEY}


@pytest.fixture
def sample_pdf_bytes() -> bytes:
    return (FIXTURES_DIR / "sample_sds.pdf").read_bytes()
