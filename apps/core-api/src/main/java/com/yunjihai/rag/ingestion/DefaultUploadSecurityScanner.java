package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipInputStream;

public final class DefaultUploadSecurityScanner implements UploadSecurityScanner {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType PPTX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final byte[] PDF_ENCRYPT = "/Encrypt".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final UploadPolicy policy;
    private final MalwareScanner malwareScanner;

    public DefaultUploadSecurityScanner(UploadPolicy policy, MalwareScanner malwareScanner) {
        this.policy = Objects.requireNonNull(policy);
        this.malwareScanner = Objects.requireNonNull(malwareScanner);
    }

    @Override
    public UploadInspection inspect(Path isolatedFile, String originalFilename, MediaType declaredMediaType) {
        try {
            var detected = detect(isolatedFile);
            var malicious = malwareScanner.isMalicious(isolatedFile);
            return new UploadInspection(
                    detected.mediaType(), detected.encrypted(), detected.archiveBomb(), malicious, detected.detail());
        } catch (IOException exception) {
            throw new UploadRejectedException("文件安全检查失败", exception);
        }
    }

    private Detection detect(Path file) throws IOException {
        var header = readHeader(file, 8);
        if (startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return new Detection(MediaType.APPLICATION_PDF, contains(file, PDF_ENCRYPT), false, "pdf");
        }
        if (startsWith(header, PNG_SIGNATURE)) {
            return new Detection(MediaType.IMAGE_PNG, false, false, "png");
        }
        if (header.length >= 4 && header[0] == 'P' && header[1] == 'K') {
            return inspectOoxml(file);
        }
        var text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.indexOf('\0') < 0) {
            return new Detection(MediaType.TEXT_MARKDOWN, false, false, "utf-8 text");
        }
        return new Detection(MediaType.APPLICATION_OCTET_STREAM, false, false, "unknown");
    }

    private Detection inspectOoxml(Path file) throws IOException {
        var entries = 0;
        long expanded = 0;
        var hasWord = false;
        var hasExcel = false;
        var hasPowerPoint = false;
        var bomb = false;
        var buffer = new byte[8192];
        var compressedLength = Files.size(file);
        try (var zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                entries++;
                var name = entry.getName();
                hasWord |= name.startsWith("word/");
                hasExcel |= name.startsWith("xl/");
                hasPowerPoint |= name.startsWith("ppt/");
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > policy.maxExpandedBytes()
                            || expanded > safeMultiply(compressedLength, policy.maxCompressionRatio())) {
                        bomb = true;
                        break;
                    }
                }
                if (entries > policy.maxArchiveEntries()) {
                    bomb = true;
                }
                if (bomb && (hasWord || hasExcel || hasPowerPoint)) {
                    break;
                }
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (hasWord) {
            mediaType = DOCX;
        } else if (hasExcel) {
            mediaType = XLSX;
        } else if (hasPowerPoint) {
            mediaType = PPTX;
        }
        return new Detection(mediaType, false, bomb, "ooxml entries=" + entries + ", expanded=" + expanded);
    }

    private static long safeMultiply(long value, int factor) {
        try {
            return Math.multiplyExact(value, factor);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static byte[] readHeader(Path file, int length) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return input.readNBytes(length);
        }
    }

    private static boolean startsWith(byte[] actual, byte[] expected) {
        if (actual.length < expected.length) {
            return false;
        }
        for (var index = 0; index < expected.length; index++) {
            if (actual[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(Path file, byte[] needle) throws IOException {
        var matched = 0;
        try (var input = Files.newInputStream(file)) {
            int value;
            while ((value = input.read()) != -1) {
                if ((byte) value == needle[matched]) {
                    matched++;
                    if (matched == needle.length) {
                        return true;
                    }
                } else {
                    matched = (byte) value == needle[0] ? 1 : 0;
                }
            }
        }
        return false;
    }

    private record Detection(MediaType mediaType, boolean encrypted, boolean archiveBomb, String detail) {
    }
}
