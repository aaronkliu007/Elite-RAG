from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ErrorCode(StrEnum):
    UNSUPPORTED = "UNSUPPORTED"
    ENCRYPTED = "ENCRYPTED"
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    CONVERSION_FAILED = "CONVERSION_FAILED"
    TIMEOUT = "TIMEOUT"


class ParserStatus(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


SupportedMediaType = Literal[
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/markdown",
    "image/png",
]
NodeKind = Literal[
    "TITLE",
    "HEADING",
    "PARAGRAPH",
    "LIST_ITEM",
    "TABLE",
    "IMAGE",
    "CAPTION",
    "PAGE_BREAK",
]


class ParserJob(StrictModel):
    schemaVersion: str = Field(pattern="^parser-job-v1$")
    jobId: UUID
    filename: str = Field(min_length=1, max_length=255)
    mediaType: SupportedMediaType
    sizeBytes: int = Field(ge=1, le=100 * 1024 * 1024)
    sha256: str = Field(pattern="^[0-9a-f]{64}$")
    timeoutSeconds: int = Field(ge=1, le=300)


class BoundingBox(StrictModel):
    x: float = Field(ge=0)
    y: float = Field(ge=0)
    width: float = Field(ge=0)
    height: float = Field(ge=0)


class TableCell(StrictModel):
    row: int = Field(ge=0)
    column: int = Field(ge=0)
    rowSpan: int = Field(ge=1)
    columnSpan: int = Field(ge=1)
    text: str


class CanonicalNode(StrictModel):
    nodeId: str = Field(min_length=1, max_length=128)
    kind: NodeKind
    text: str = ""
    parentNodeId: str | None = None
    page: int | None = Field(default=None, ge=1)
    bbox: BoundingBox | None = None
    tableCells: list[TableCell] | None = None
    confidence: float | None = Field(default=None, ge=0, le=1)


class CanonicalBody(StrictModel):
    sourceFilename: str
    mediaType: str
    pageCount: int = Field(ge=1)
    nodes: list[CanonicalNode]


class ParserError(StrictModel):
    code: ErrorCode
    message: str = Field(min_length=1, max_length=500)
    retryable: bool


class CanonicalDocumentV1(StrictModel):
    schemaVersion: str = "canonical-document-v1"
    jobId: UUID
    status: ParserStatus
    document: CanonicalBody | None
    errors: list[ParserError]

    @classmethod
    def succeeded(
        cls, job: ParserJob, page_count: int, nodes: list[CanonicalNode]
    ) -> "CanonicalDocumentV1":
        return cls(
            jobId=job.jobId,
            status=ParserStatus.SUCCEEDED,
            document=CanonicalBody(
                sourceFilename=job.filename,
                mediaType=job.mediaType,
                pageCount=page_count,
                nodes=nodes,
            ),
            errors=[],
        )

    @classmethod
    def failed(
        cls, job: ParserJob, code: ErrorCode, message: str, retryable: bool
    ) -> "CanonicalDocumentV1":
        return cls(
            jobId=job.jobId,
            status=ParserStatus.FAILED,
            document=None,
            errors=[ParserError(code=code, message=message, retryable=retryable)],
        )


class ParserFailure(Exception):
    def __init__(self, code: ErrorCode, safe_message: str, retryable: bool) -> None:
        super().__init__(safe_message)
        self.code = code
        self.safe_message = safe_message
        self.retryable = retryable
