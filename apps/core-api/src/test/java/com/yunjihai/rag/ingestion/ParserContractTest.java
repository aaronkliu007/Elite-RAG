package com.yunjihai.rag.ingestion;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParserContractTest {

    @Test
    void sharedSchemasAcceptVersionedJobAndCanonicalTable() throws Exception {
        var root = Path.of("..", "..").toAbsolutePath().normalize();
        var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        var jobSchema = factory.getSchema(Files.newInputStream(
                root.resolve("contracts/parser/parser-job-v1.schema.json")));
        var documentSchema = factory.getSchema(Files.newInputStream(
                root.resolve("contracts/parser/canonical-document-v1.schema.json")));

        var job = """
                {"schemaVersion":"parser-job-v1","jobId":"00000000-0000-0000-0000-000000000701",
                 "filename":"complex-table.md","mediaType":"text/markdown","sizeBytes":42,
                 "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "timeoutSeconds":30}
                """;
        var document = """
                {"schemaVersion":"canonical-document-v1","jobId":"00000000-0000-0000-0000-000000000701",
                 "status":"SUCCEEDED","document":{"sourceFilename":"complex-table.md","mediaType":"text/markdown",
                 "pageCount":1,"nodes":[{"nodeId":"n1","kind":"TABLE","text":"项目|负责人",
                 "parentNodeId":null,"page":1,"bbox":null,"tableCells":[{"row":0,"column":0,
                 "rowSpan":1,"columnSpan":1,"text":"项目"}],"confidence":1.0}]},"errors":[]}
                """;

        assertThat(jobSchema.validate(job, com.networknt.schema.InputFormat.JSON)).isEmpty();
        assertThat(documentSchema.validate(document, com.networknt.schema.InputFormat.JSON)).isEmpty();
    }

    @Test
    void parserJobRejectsUnsupportedMediaTypeAndUnsafeFilename() {
        assertThatThrownBy(() -> job("complex-table.zip", "application/zip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("媒体类型");
        assertThatThrownBy(() -> job("../complex-table.md", "text/markdown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件名");
        assertThatThrownBy(() -> job("complex-table.md", "text/markdown\r\nX-Injected: true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("媒体类型");
    }

    private static ParserJob job(String filename, String mediaType) {
        return new ParserJob(
                "parser-job-v1",
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000701"),
                filename,
                mediaType,
                4,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                30);
    }
}
