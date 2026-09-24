import json
from pathlib import Path

from jsonschema import Draft202012Validator

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CONTRACTS = REPOSITORY_ROOT / "contracts" / "parser"


def _load(name: str) -> dict[str, object]:
    return json.loads((CONTRACTS / name).read_text(encoding="utf-8"))


def test_parser_contracts_are_valid_draft_2020_12_schemas() -> None:
    Draft202012Validator.check_schema(_load("parser-job-v1.schema.json"))
    Draft202012Validator.check_schema(_load("canonical-document-v1.schema.json"))


def test_job_and_complex_table_document_match_shared_contracts() -> None:
    job = {
        "schemaVersion": "parser-job-v1",
        "jobId": "00000000-0000-0000-0000-000000000701",
        "filename": "complex-table.md",
        "mediaType": "text/markdown",
        "sizeBytes": 42,
        "sha256": "a" * 64,
        "timeoutSeconds": 30,
    }
    document = {
        "schemaVersion": "canonical-document-v1",
        "jobId": job["jobId"],
        "status": "SUCCEEDED",
        "document": {
            "sourceFilename": job["filename"],
            "mediaType": job["mediaType"],
            "pageCount": 1,
            "nodes": [
                {
                    "nodeId": "n1",
                    "kind": "TABLE",
                    "text": "项目|负责人",
                    "parentNodeId": None,
                    "page": 1,
                    "bbox": None,
                    "tableCells": [
                        {"row": 0, "column": 0, "rowSpan": 1, "columnSpan": 1, "text": "项目"},
                        {"row": 0, "column": 1, "rowSpan": 1, "columnSpan": 1, "text": "负责人"},
                    ],
                    "confidence": 1.0,
                }
            ],
        },
        "errors": [],
    }

    Draft202012Validator(_load("parser-job-v1.schema.json")).validate(job)
    Draft202012Validator(_load("canonical-document-v1.schema.json")).validate(document)


def test_structured_error_codes_match_contract() -> None:
    schema = Draft202012Validator(_load("canonical-document-v1.schema.json"))
    for code in (
        "UNSUPPORTED",
        "ENCRYPTED",
        "LIMIT_EXCEEDED",
        "CONVERSION_FAILED",
        "TIMEOUT",
    ):
        schema.validate(
            {
                "schemaVersion": "canonical-document-v1",
                "jobId": "00000000-0000-0000-0000-000000000701",
                "status": "FAILED",
                "document": None,
                "errors": [{"code": code, "message": "解析失败", "retryable": False}],
            }
        )
