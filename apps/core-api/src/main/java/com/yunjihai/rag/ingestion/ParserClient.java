package com.yunjihai.rag.ingestion;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ParserClient {

    private final ParserTransport transport;
    private final ParserContractValidator validator;
    private final ObjectMapper json;

    public ParserClient(ParserTransport transport, ParserContractValidator validator) {
        this.transport = Objects.requireNonNull(transport);
        this.validator = Objects.requireNonNull(validator);
        this.json = new ObjectMapper();
    }

    public CanonicalDocumentV1 parse(ParserJob job, Path sourceFile) {
        try {
            if (Files.size(sourceFile) != job.sizeBytes()) {
                throw new IllegalArgumentException("解析源文件大小与任务不一致");
            }
            var responseBody = transport.parse(job, sourceFile);
            validator.validate(responseBody);
            var response = json.readValue(responseBody, CanonicalDocumentV1.class);
            if (!job.jobId().equals(response.jobId())) {
                throw new InvalidParserResponseException("解析响应 jobId 与请求不一致");
            }
            if (response.document() != null
                    && (!job.filename().equals(response.document().sourceFilename())
                    || !job.mediaType().equals(response.document().mediaType()))) {
                throw new InvalidParserResponseException("解析响应来源与请求不一致");
            }
            return response;
        } catch (InvalidParserResponseException | IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InvalidParserResponseException("无法读取或解析解析服务响应", exception);
        }
    }
}
