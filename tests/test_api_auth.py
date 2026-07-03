def test_healthz_requires_no_auth(client):
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_extract_without_api_key_is_unauthorized(client, sample_pdf_bytes):
    response = client.post(
        "/v1/sds/extract",
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )
    assert response.status_code == 401
    assert response.json()["error"]["type"] == "unauthorized"


def test_extract_with_wrong_api_key_is_unauthorized(client, sample_pdf_bytes):
    response = client.post(
        "/v1/sds/extract",
        headers={"X-API-Key": "wrong-key"},
        files={"file": ("sample_sds.pdf", sample_pdf_bytes, "application/pdf")},
    )
    assert response.status_code == 401
