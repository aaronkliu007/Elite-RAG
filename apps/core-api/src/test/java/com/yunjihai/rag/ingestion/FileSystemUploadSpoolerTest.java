package com.yunjihai.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemUploadSpoolerTest {

    @Test
    void usesDedicatedDirectoryAndRemovesRejectedPartialFile() throws Exception {
        var quarantine = Files.createTempDirectory("rag-quarantine-test-");
        try {
            var spooler = new FileSystemUploadSpooler(quarantine);

            assertThatThrownBy(() -> spooler.spool(new ByteArrayInputStream(new byte[9]), 8))
                    .isInstanceOf(UploadRejectedException.class);
            try (var entries = Files.list(quarantine)) {
                assertThat(entries).isEmpty();
            }
        } finally {
            Files.deleteIfExists(quarantine);
        }
    }
}
