package com.yunjihai.rag.ingestion;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ParserContractValidator {

    private final JsonSchema schema;

    private ParserContractValidator(JsonSchema schema) {
        this.schema = schema;
    }

    public static ParserContractValidator from(Path schemaFile) {
        try (var input = Files.newInputStream(schemaFile)) {
            return from(input);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载解析响应契约", exception);
        }
    }

    public static ParserContractValidator from(InputStream schemaInput) {
        if (schemaInput == null) {
            throw new IllegalArgumentException("解析响应契约不能为空");
        }
        return new ParserContractValidator(
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaInput));
    }

    public void validate(String responseBody) {
        var violations = schema.validate(responseBody, InputFormat.JSON);
        if (!violations.isEmpty()) {
            throw new InvalidParserResponseException("解析服务响应不符合 canonical-document-v1 契约");
        }
    }
}
