import json
import sys
from pathlib import Path

from parser_worker.docling_adapter import DoclingConverter
from parser_worker.models import ErrorCode, ParserFailure


def _write_result(source_path: Path, result_path: Path) -> None:
    try:
        result = DoclingConverter._convert_sync(source_path)
        payload: dict[str, object] = {
            "pageCount": result.page_count,
            "nodes": [node.model_dump(mode="json") for node in result.nodes],
        }
    except ParserFailure as failure:
        payload = {
            "error": {
                "code": failure.code.value,
                "message": failure.safe_message,
                "retryable": failure.retryable,
            }
        }
    except Exception:
        payload = {
            "error": {
                "code": ErrorCode.CONVERSION_FAILED.value,
                "message": "文档转换失败",
                "retryable": True,
            }
        }
    result_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        return 2
    _write_result(Path(sys.argv[1]), Path(sys.argv[2]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
