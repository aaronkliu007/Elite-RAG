from fastapi.testclient import TestClient

from parser_worker.app import app


def test_health_reports_parser_service_ready() -> None:
    response = TestClient(app).get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "document-parser"}
