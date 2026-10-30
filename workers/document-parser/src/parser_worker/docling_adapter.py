import asyncio
import importlib
import json
import logging
import sys
import tempfile
from collections.abc import Iterable
from contextlib import suppress
from pathlib import Path
from typing import Any, cast

from parser_worker.models import CanonicalNode, ErrorCode, NodeKind, ParserFailure, TableCell
from parser_worker.service import ConversionResult

LOGGER = logging.getLogger(__name__)


class DoclingConverter:
    async def convert(self, path: Path, filename: str, media_type: str) -> ConversionResult:
        with tempfile.NamedTemporaryFile(
            prefix="parser-result-", suffix=".json", delete=False
        ) as result_file:
            result_path = Path(result_file.name)
        try:
            process = await asyncio.create_subprocess_exec(
                sys.executable,
                "-m",
                "parser_worker.docling_runner",
                str(path),
                str(result_path),
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            try:
                return_code = await process.wait()
            except asyncio.CancelledError:
                with suppress(ProcessLookupError):
                    process.kill()
                await process.wait()
                raise
            if return_code != 0 or not result_path.is_file():
                raise ParserFailure(ErrorCode.CONVERSION_FAILED, "文档转换失败", True)
            return self._read_result(result_path)
        finally:
            try:
                result_path.unlink(missing_ok=True)
            except OSError as cleanup_failure:
                LOGGER.warning(
                    "parser result cleanup failed exception_type=%s",
                    type(cleanup_failure).__name__,
                )

    @staticmethod
    def _convert_sync(path: Path) -> ConversionResult:
        try:
            module = importlib.import_module("docling.document_converter")
            document_converter: Any = module.DocumentConverter
            base_models = importlib.import_module("docling.datamodel.base_models")
            pdf_backend = importlib.import_module("docling.backend.pypdfium2_backend")
        except ImportError as exception:
            raise ParserFailure(
                ErrorCode.CONVERSION_FAILED, "解析引擎尚未安装", True
            ) from exception

        try:
            format_options = {
                base_models.InputFormat.PDF: module.PdfFormatOption(
                    backend=pdf_backend.PyPdfiumDocumentBackend
                )
            }
            converted = document_converter(format_options=format_options).convert(str(path))
            exported: dict[str, Any] = converted.document.export_to_dict()
            nodes = DoclingConverter._map_nodes(exported)
            page_count = DoclingConverter._page_count(exported, nodes)
            return ConversionResult(page_count=page_count, nodes=nodes)
        except ParserFailure:
            raise
        except Exception as exception:
            name = type(exception).__name__.lower()
            if "encrypt" in name or "password" in name:
                raise ParserFailure(ErrorCode.ENCRYPTED, "暂不支持加密文档", False) from exception
            raise ParserFailure(ErrorCode.CONVERSION_FAILED, "文档转换失败", True) from exception

    @staticmethod
    def _read_result(result_path: Path) -> ConversionResult:
        try:
            payload = json.loads(result_path.read_text(encoding="utf-8"))
            error = payload.get("error")
            if isinstance(error, dict):
                raise ParserFailure(
                    ErrorCode(error["code"]),
                    str(error["message"]),
                    bool(error["retryable"]),
                )
            return ConversionResult(
                page_count=int(payload["pageCount"]),
                nodes=[CanonicalNode.model_validate(node) for node in payload["nodes"]],
            )
        except ParserFailure:
            raise
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
            raise ParserFailure(
                ErrorCode.CONVERSION_FAILED, "解析引擎返回无效结果", True
            ) from exception

    @staticmethod
    def _map_nodes(exported: dict[str, Any]) -> list[CanonicalNode]:
        nodes: list[CanonicalNode] = []
        reference_items: dict[str, dict[str, Any]] = {}
        for group in ("groups", "texts", "tables", "pictures"):
            values = exported.get(group, [])
            if isinstance(values, list):
                reference_items.update({
                    f"#/{group}/{index}": item
                    for index, item in enumerate(values) if isinstance(item, dict)
                })
        emitted: dict[str, str] = {}
        for index, item in enumerate(_items(exported)):
            label = str(item.get("label", "paragraph")).upper()
            if label == "SECTION_HEADER":
                label = "HEADING"
            kind = (
                label
                if label
                in {
                    "TITLE",
                    "HEADING",
                    "PARAGRAPH",
                    "LIST_ITEM",
                    "TABLE",
                    "IMAGE",
                    "CAPTION",
                    "PAGE_BREAK",
                }
                else "PARAGRAPH"
            )
            cells = None
            if kind == "TABLE":
                cells = _table_cells(item)
            text = str(item.get("text", ""))
            if kind == "TABLE" and not text and cells:
                text = "\n".join(cell.text for cell in cells if cell.text)
            node_id = str(item.get("self_ref", f"node-{index + 1}"))
            nodes.append(
                CanonicalNode(
                    nodeId=node_id,
                    kind=cast(NodeKind, kind),
                    text=text,
                    parentNodeId=_nearest_emitted_parent(item, reference_items, emitted),
                    page=_page(item),
                    tableCells=cells,
                    confidence=_confidence(item),
                )
            )
            emitted[node_id] = node_id
        return nodes

    @staticmethod
    def _page_count(exported: dict[str, Any], nodes: list[CanonicalNode]) -> int:
        pages = exported.get("pages")
        if isinstance(pages, dict) and pages:
            return len(pages)
        return max((node.page or 1 for node in nodes), default=1)


def _items(exported: dict[str, Any]) -> Iterable[dict[str, Any]]:
    content_groups = ("texts", "tables", "pictures")
    references: dict[str, tuple[dict[str, Any], bool]] = {}
    ordered_content: list[str] = []
    for group in ("groups", *content_groups):
        values = exported.get(group, [])
        if not isinstance(values, list):
            continue
        for index, item in enumerate(values):
            if not isinstance(item, dict):
                continue
            reference = f"#/{group}/{index}"
            references[reference] = (item, group in content_groups)
            if group in content_groups:
                ordered_content.append(reference)

    def child_refs(item: dict[str, Any]) -> list[str]:
        children = item.get("children", [])
        if not isinstance(children, list):
            return []
        return [child["$ref"] for child in children
                if isinstance(child, dict) and isinstance(child.get("$ref"), str)]

    body = exported.get("body")
    pending = list(reversed(child_refs(body))) if isinstance(body, dict) else []
    visited: set[str] = set()
    while pending:
        reference = pending.pop()
        if reference in visited or reference not in references:
            continue
        visited.add(reference)
        item, is_content = references[reference]
        if is_content:
            yield item
        pending.extend(reversed(child_refs(item)))

    # Keep content omitted from the body tree, without pretending its original position is known.
    for reference in ordered_content:
        if reference not in visited:
            yield references[reference][0]


def _nearest_emitted_parent(
    item: dict[str, Any], references: dict[str, dict[str, Any]], emitted: dict[str, str]
) -> str | None:
    visited: set[str] = set()
    parent = item.get("parent")
    while isinstance(parent, dict):
        ref = parent.get("$ref")
        if not isinstance(ref, str) or ref in visited:
            break
        if ref in emitted:
            return emitted[ref]
        visited.add(ref)
        parent_item = references.get(ref)
        if parent_item is None:
            break
        parent = parent_item.get("parent")
    return None


def _page(item: dict[str, Any]) -> int | None:
    provenance = item.get("prov")
    if isinstance(provenance, list) and provenance and isinstance(provenance[0], dict):
        value = provenance[0].get("page_no")
        if isinstance(value, int):
            return value
    return None


def _confidence(item: dict[str, Any]) -> float | None:
    value = item.get("confidence")
    return float(value) if isinstance(value, int | float) else None


def _table_cells(item: dict[str, Any]) -> list[TableCell]:
    data = item.get("data")
    raw_cells = data.get("table_cells", []) if isinstance(data, dict) else []
    return [
        TableCell(
            row=_integer(cell, "start_row_offset_idx", "row", default=0),
            column=_integer(cell, "start_col_offset_idx", "column", default=0),
            rowSpan=_integer(cell, "row_span", default=1),
            columnSpan=_integer(cell, "col_span", "column_span", default=1),
            text=str(cell.get("text", "")),
        )
        for cell in raw_cells
        if isinstance(cell, dict)
    ]


def _integer(cell: dict[str, Any], *names: str, default: int) -> int:
    for name in names:
        value = cell.get(name)
        if isinstance(value, int) and not isinstance(value, bool):
            return value
    return default
