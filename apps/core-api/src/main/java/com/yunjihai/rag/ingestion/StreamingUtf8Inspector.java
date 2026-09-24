package com.yunjihai.rag.ingestion;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class StreamingUtf8Inspector {

    private StreamingUtf8Inspector() {
    }

    static boolean isText(Path file) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var buffer = new char[8192];
        try (var reader = new InputStreamReader(Files.newInputStream(file), decoder)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (var index = 0; index < read; index++) {
                    if (buffer[index] == '\0') {
                        return false;
                    }
                }
            }
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }
}
