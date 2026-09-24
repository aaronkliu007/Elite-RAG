package com.yunjihai.rag.ingestion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThatCode;

class SpooledUploadTest {

    @Test
    void cleanupFailureDoesNotTurnCommittedUploadIntoFailure() throws Exception {
        var nonEmptyDirectory = Files.createTempDirectory("spooled-upload-cleanup-test-");
        Files.writeString(nonEmptyDirectory.resolve("keeps-directory-non-empty"), "x");
        var logger = Logger.getLogger(SpooledUpload.class.getName());
        var previousLevel = logger.getLevel();
        try {
            logger.setLevel(Level.OFF);
            var upload = new SpooledUpload(nonEmptyDirectory, 1, "sha");

            assertThatCode(upload::close).doesNotThrowAnyException();
        } finally {
            logger.setLevel(previousLevel);
            Files.deleteIfExists(nonEmptyDirectory.resolve("keeps-directory-non-empty"));
            Files.deleteIfExists(nonEmptyDirectory);
        }
    }
}
