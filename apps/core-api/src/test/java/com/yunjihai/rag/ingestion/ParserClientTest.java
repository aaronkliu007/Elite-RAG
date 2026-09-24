package com.yunjihai.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParserClientTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");

    @Test
    void validatesAndMapsSuccessfulResponse() throws Exception {
        var source = Files.createTempFile("parser-client-", ".md");
        try {
            Files.writeString(source, "table");
            var client = clientReturning(successResponse(JOB_ID.toString()));

            var document = client.parse(job(Files.size(source)), source);

            assertThat(document.status()).isEqualTo(ParserStatus.SUCCEEDED);
            assertThat(document.document().nodes()).singleElement()
                    .satisfies(node -> assertThat(node.tableCells()).singleElement()
                            .satisfies(cell -> assertThat(cell.text()).isEqualTo("项目")));
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void rejectsResponseThatViolatesSchemaOrJobIdentity() throws Exception {
        var source = Files.createTempFile("parser-client-", ".md");
        try {
            Files.writeString(source, "table");
            var missingNodeId = successResponse(JOB_ID.toString()).replace("\"nodeId\":\"n1\",", "");
            var wrongJob = successResponse("00000000-0000-0000-0000-000000000799");
            var wrongSource = successResponse(JOB_ID.toString())
                    .replace("\"sourceFilename\":\"complex-table.md\"", "\"sourceFilename\":\"other.md\"");
            var wrongMediaType = successResponse(JOB_ID.toString())
                    .replace("\"mediaType\":\"text/markdown\"", "\"mediaType\":\"application/pdf\"");

            assertThatThrownBy(() -> clientReturning(missingNodeId).parse(job(Files.size(source)), source))
                    .isInstanceOf(InvalidParserResponseException.class);
            assertThatThrownBy(() -> clientReturning(wrongJob).parse(job(Files.size(source)), source))
                    .isInstanceOf(InvalidParserResponseException.class)
                    .hasMessageContaining("jobId");
            assertThatThrownBy(() -> clientReturning(wrongSource).parse(job(Files.size(source)), source))
                    .isInstanceOf(InvalidParserResponseException.class)
                    .hasMessageContaining("来源");
            assertThatThrownBy(() -> clientReturning(wrongMediaType).parse(job(Files.size(source)), source))
                    .isInstanceOf(InvalidParserResponseException.class)
                    .hasMessageContaining("来源");
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private static ParserClient clientReturning(String response) throws Exception {
        var root = java.nio.file.Path.of("..", "..").toAbsolutePath().normalize();
        return new ParserClient(
                (job, file) -> response,
                ParserContractValidator.from(root.resolve("contracts/parser/canonical-document-v1.schema.json")));
    }

    private static ParserJob job(long size) {
        return new ParserJob(
                "parser-job-v1", JOB_ID, "complex-table.md", "text/markdown", size,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 30);
    }

    private static String successResponse(String jobId) {
        return """
                {"schemaVersion":"canonical-document-v1","jobId":"%s","status":"SUCCEEDED",
                 "document":{"sourceFilename":"complex-table.md","mediaType":"text/markdown","pageCount":1,
                 "nodes":[{"nodeId":"n1","kind":"TABLE","text":"项目","parentNodeId":null,"page":1,
                 "bbox":null,"tableCells":[{"row":0,"column":0,"rowSpan":1,"columnSpan":1,"text":"项目"}],
                 "confidence":1.0}]},"errors":[]}
                """.formatted(jobId);
    }
}
