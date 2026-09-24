import asyncio
import hashlib
import json
import logging
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from parser_worker.app import app, get_parser_service
from parser_worker.models import CanonicalNode, ErrorCode, ParserFailure, TableCell
from parser_worker.service import ConversionResult, ParserService


class FakeConverter:
    def __init__(self, result: ConversionResult | Exception) -> None:
        self.result = result
        self.calls = 0
        self.last_path: Path | None = None

    async def convert(self, path: Path, filename: str, media_type: str) -> ConversionResult:
        self.calls += 1
        self.last_path = path
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


class SlowConverter:
    async def convert(self, path: Path, filename: str, media_type: str) -> ConversionResult:
        await asyncio.sleep(2)
        return ConversionResult(page_count=1, nodes=[])


@contextmanager
def _service(converter: FakeConverter, **limits: int) -> Iterator[None]:
    service = ParserService(converter, **limits)
    app.dependency_overrides[get_parser_service] = lambda: service
    try:
        yield
    finally:
        app.dependency_overrides.clear()


def _job(content: bytes, **updates: Any) -> dict[str, object]:
    job: dict[str, object] = {
        "schemaVersion": "parser-job-v1",
        "jobId": "00000000-0000-0000-0000-000000000701",
        "filename": "complex-table.md",
        "mediaType": "text/markdown",
        "sizeBytes": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
        "timeoutSeconds": 1,
    }
    job.update(updates)
    return job


def _post(content: bytes, job: dict[str, object]) -> Any:
    return TestClient(app).post(
        "/v1/parse",
        data={"job": json.dumps(job)},
        files={
            "file": (
                job.get("filename", "file.bin"),
                content,
                job.get("mediaType", "application/octet-stream"),
            )
        },
    )


def test_parse_returns_complex_table_without_logging_body(caplog: pytest.LogCaptureFixture) -> None:
    secret = "仅用于测试且不得进入日志的正文"
    content = secret.encode()
    converter = FakeConverter(
        ConversionResult(
            page_count=1,
            nodes=[
                CanonicalNode(
                    nodeId="table-1",
                    kind="TABLE",
                    text="项目|负责人",
                    page=1,
                    tableCells=[
                        TableCell(row=0, column=0, rowSpan=1, columnSpan=1, text="项目"),
                        TableCell(row=0, column=1, rowSpan=1, columnSpan=1, text="负责人"),
                    ],
                )
            ],
        )
    )
    caplog.set_level(logging.INFO)
    with _service(converter):
        response = _post(content, _job(content))

    assert response.status_code == 200
    assert response.json()["status"] == "SUCCEEDED"
    assert response.json()["document"]["nodes"][0]["tableCells"][1]["text"] == "负责人"
    assert secret not in caplog.text


def test_remote_source_field_is_rejected_before_conversion() -> None:
    content = b"safe"
    converter = FakeConverter(ConversionResult(page_count=1, nodes=[]))
    with _service(converter):
        response = _post(content, _job(content, sourceUrl="https://attacker.invalid/file.pdf"))

    assert response.status_code == 400
    assert converter.calls == 0


def test_unknown_media_type_is_rejected_before_conversion() -> None:
    content = b"safe"
    converter = FakeConverter(ConversionResult(page_count=1, nodes=[]))
    with _service(converter):
        response = _post(content, _job(content, mediaType="application/zip"))

    assert response.status_code == 400
    assert converter.calls == 0


@pytest.mark.parametrize(
    ("failure", "expected"),
    [
        (ParserFailure(ErrorCode.ENCRYPTED, "加密文档", False), "ENCRYPTED"),
        (ParserFailure(ErrorCode.UNSUPPORTED, "不支持", False), "UNSUPPORTED"),
        (RuntimeError("converter crashed with body content"), "CONVERSION_FAILED"),
    ],
)
def test_converter_failures_are_structured(failure: Exception, expected: str) -> None:
    content = b"safe"
    with _service(FakeConverter(failure)):
        response = _post(content, _job(content))

    assert response.status_code == 200
    assert response.json()["status"] == "FAILED"
    assert response.json()["errors"][0]["code"] == expected
    assert "body content" not in response.text


def test_file_and_page_limits_return_limit_exceeded() -> None:
    content = b"12345"
    page_heavy = FakeConverter(ConversionResult(page_count=3, nodes=[]))
    with _service(page_heavy, max_bytes=4, max_pages=2):
        too_large = _post(content, _job(content))
    with _service(page_heavy, max_bytes=10, max_pages=2):
        too_many_pages = _post(content, _job(content))

    assert too_large.json()["errors"][0]["code"] == "LIMIT_EXCEEDED"
    assert too_many_pages.json()["errors"][0]["code"] == "LIMIT_EXCEEDED"


def test_timeout_is_reported_with_fixed_error_code() -> None:
    content = b"safe"
    service = ParserService(SlowConverter())
    app.dependency_overrides[get_parser_service] = lambda: service
    try:
        response = _post(content, _job(content, timeoutSeconds=1))
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["errors"][0]["code"] == "TIMEOUT"


def test_quarantine_cleanup_failure_does_not_override_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    content = b"safe"
    converter = FakeConverter(ConversionResult(page_count=1, nodes=[]))
    original_unlink = Path.unlink

    def fail_parser_cleanup(path: Path, missing_ok: bool = False) -> None:
        if path.name.startswith("parser-quarantine-"):
            raise PermissionError("cleanup denied")
        original_unlink(path, missing_ok=missing_ok)

    with _service(converter), monkeypatch.context() as patch:
        patch.setattr(Path, "unlink", fail_parser_cleanup)
        response = _post(content, _job(content))

    assert response.status_code == 200
    assert response.json()["status"] == "SUCCEEDED"
    assert converter.last_path is not None
    original_unlink(converter.last_path, missing_ok=True)
