import logging
from typing import Annotated

from fastapi import Depends, FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ValidationError

from parser_worker.docling_adapter import DoclingConverter
from parser_worker.models import CanonicalDocumentV1, ParserJob
from parser_worker.service import ParserService


class HealthResponse(BaseModel):
    status: str
    service: str


app = FastAPI(title="云基华海文档解析服务", version="0.1.0")
_default_service = ParserService(DoclingConverter())
LOGGER = logging.getLogger(__name__)


def get_parser_service() -> ParserService:
    return _default_service


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok", service="document-parser")


@app.post("/v1/parse", response_model=CanonicalDocumentV1)
async def parse_document(
    job: Annotated[str, Form()],
    file: Annotated[UploadFile, File()],
    service: Annotated[ParserService, Depends(get_parser_service)],
) -> CanonicalDocumentV1 | JSONResponse:
    try:
        parser_job = ParserJob.model_validate_json(job)
    except ValidationError:
        LOGGER.warning("parser request rejected: invalid job metadata")
        return JSONResponse(status_code=400, content={"detail": "解析任务元数据无效"})
    return await service.parse(parser_job, file)
