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

    @Test
    void handlesMultibyteCharacterAcrossBufferBoundaries() throws Exception {
        var file = Files.createTempFile("utf8-boundary-", ".md");
        try {
            var prefix = "a".repeat(8191);
            Files.writeString(file, prefix + "知" + "识");

            assertThat(StreamingUtf8Inspector.isText(file)).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsTruncatedInvalidAndNullBytes() throws Exception {
        var truncated = Files.createTempFile("utf8-truncated-", ".md");
        var invalid = Files.createTempFile("utf8-invalid-", ".md");
        var nullByte = Files.createTempFile("utf8-null-", ".md");
        try {
            Files.write(truncated, new byte[]{'a', (byte) 0xE4, (byte) 0xB8});
            Files.write(invalid, new byte[]{'a', (byte) 0x80});
            Files.write(nullByte, new byte[]{'a', 0, 'b'});

            assertThat(StreamingUtf8Inspector.isText(truncated)).isFalse();
            assertThat(StreamingUtf8Inspector.isText(invalid)).isFalse();
            assertThat(StreamingUtf8Inspector.isText(nullByte)).isFalse();
        } finally {
            Files.deleteIfExists(truncated);
            Files.deleteIfExists(invalid);
            Files.deleteIfExists(nullByte);
        }
    }
}
