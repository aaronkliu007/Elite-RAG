import asyncio
import hashlib
import logging
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from fastapi import UploadFile

from parser_worker.models import (
    CanonicalDocumentV1,
    CanonicalNode,
    ErrorCode,
    ParserFailure,
    ParserJob,
)

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class ConversionResult:
    page_count: int
    nodes: list[CanonicalNode]


class DocumentConverter(Protocol):
    async def convert(self, path: Path, filename: str, media_type: str) -> ConversionResult: ...


class ParserService:
    def __init__(
        self,
        converter: DocumentConverter,
        *,
        max_bytes: int = 100 * 1024 * 1024,
        max_pages: int = 500,
        max_nodes: int = 1_000_000,
    ) -> None:
        self._converter = converter
        self._max_bytes = max_bytes
        self._max_pages = max_pages
        self._max_nodes = max_nodes

    async def parse(self, job: ParserJob, upload: UploadFile) -> CanonicalDocumentV1:
        temp_path: Path | None = None
        try:
            self._validate_metadata(job, upload)
            temp_path = await self._spool(job, upload)
            result = await asyncio.wait_for(
                self._converter.convert(temp_path, job.filename, job.mediaType),
                timeout=job.timeoutSeconds,
            )
            self._validate_result(result)
            LOGGER.info("parser job succeeded job_id=%s pages=%d", job.jobId, result.page_count)
            return CanonicalDocumentV1.succeeded(job, result.page_count, result.nodes)
        except ParserFailure as failure:
            LOGGER.warning("parser job rejected job_id=%s code=%s", job.jobId, failure.code)
            return CanonicalDocumentV1.failed(
                job, failure.code, failure.safe_message, failure.retryable
            )
        except TimeoutError:
            LOGGER.warning("parser job timed out job_id=%s", job.jobId)
            return CanonicalDocumentV1.failed(job, ErrorCode.TIMEOUT, "文档解析超时", True)
        except Exception as exception:
            LOGGER.error(
                "parser job failed job_id=%s exception_type=%s",
                job.jobId,
                type(exception).__name__,
            )
            return CanonicalDocumentV1.failed(
                job, ErrorCode.CONVERSION_FAILED, "文档转换失败", True
            )
        finally:
            if temp_path is not None:
                try:
                    temp_path.unlink(missing_ok=True)
                except OSError as cleanup_failure:
                    LOGGER.warning(
                        "parser quarantine cleanup failed job_id=%s exception_type=%s",
                        job.jobId,
                        type(cleanup_failure).__name__,
                    )

    def _validate_metadata(self, job: ParserJob, upload: UploadFile) -> None:
        if upload.filename != job.filename or upload.content_type != job.mediaType:
            raise ParserFailure(ErrorCode.UNSUPPORTED, "文件元数据不一致", False)
        if Path(job.filename).name != job.filename or "://" in job.filename:
            raise ParserFailure(ErrorCode.UNSUPPORTED, "禁止远程路径或目录路径", False)
        if job.sizeBytes > self._max_bytes:
            raise ParserFailure(ErrorCode.LIMIT_EXCEEDED, "文件超过解析大小限制", False)

    async def _spool(self, job: ParserJob, upload: UploadFile) -> Path:
        digest = hashlib.sha256()
        size = 0
        suffix = Path(job.filename).suffix.lower()
        with tempfile.NamedTemporaryFile(
            prefix="parser-quarantine-", suffix=suffix, delete=False
        ) as target:
            path = Path(target.name)
            try:
                while chunk := await upload.read(64 * 1024):
                    size += len(chunk)
                    if size > self._max_bytes:
                        raise ParserFailure(ErrorCode.LIMIT_EXCEEDED, "文件超过解析大小限制", False)
                    digest.update(chunk)
                    target.write(chunk)
            except Exception:
                path.unlink(missing_ok=True)
                raise
        if size != job.sizeBytes or digest.hexdigest() != job.sha256:
            path.unlink(missing_ok=True)
            raise ParserFailure(ErrorCode.CONVERSION_FAILED, "文件完整性校验失败", False)
        return path

    def _validate_result(self, result: ConversionResult) -> None:
        if result.page_count < 1 or result.page_count > self._max_pages:
            raise ParserFailure(ErrorCode.LIMIT_EXCEEDED, "文档页数超过解析限制", False)
        if len(result.nodes) > self._max_nodes:
            raise ParserFailure(ErrorCode.LIMIT_EXCEEDED, "文档节点数超过解析限制", False)
        node_ids = {node.nodeId for node in result.nodes}
        if len(node_ids) != len(result.nodes):
            raise ParserFailure(ErrorCode.CONVERSION_FAILED, "解析节点标识重复", False)
        for node in result.nodes:
            if node.page is not None and node.page > result.page_count:
                raise ParserFailure(ErrorCode.CONVERSION_FAILED, "解析节点页码越界", False)
            if node.parentNodeId is not None and node.parentNodeId not in node_ids:
                raise ParserFailure(ErrorCode.CONVERSION_FAILED, "解析节点父级不存在", False)
