package com.yunjihai.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingUtf8InspectorTest {

    @Test
    void validatesLargeMarkdownWithFixedSizeBuffers() throws Exception {
        var file = Files.createTempFile("large-markdown-", ".md");
        try {
            var block = "企业知识库内容\n".repeat(1024).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var output = Files.newOutputStream(file)) {
                for (var index = 0; index < 512; index++) {
                    output.write(block);
                }
            }

            assertThat(StreamingUtf8Inspector.isText(file)).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
