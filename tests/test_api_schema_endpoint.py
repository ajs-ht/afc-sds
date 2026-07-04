from app.schemas.sds import SDS_JSON_SCHEMA


def test_schema_endpoint_requires_api_key(client):
    response = client.get("/v1/sds/schema")
    assert response.status_code == 401


def test_schema_endpoint_rejects_wrong_api_key(client):
    response = client.get("/v1/sds/schema", headers={"X-API-Key": "wrong"})
    assert response.status_code == 401


def test_schema_endpoint_returns_versioned_schema(client, auth_headers):
    response = client.get("/v1/sds/schema", headers=auth_headers)

    assert response.status_code == 200
    body = response.json()
    assert body["schema_version"] == "2.0"
    assert body["json_schema"] == SDS_JSON_SCHEMA
    # The published schema must stay strict — integrators rely on it matching
    # what the extraction endpoint actually enforces.
    assert body["json_schema"]["additionalProperties"] is False
    assert "section_9_physical_chemical_properties" in body["json_schema"]["properties"]
