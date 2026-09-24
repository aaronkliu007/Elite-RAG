package com.yunjihai.rag.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultUploadSecurityScannerTest {

    @Test
    void detectsPdfEncryptionFromRealBytes() throws Exception {
        var file = Files.createTempFile("encrypted-", ".pdf");
        try {
            Files.writeString(file, "%PDF-1.7\n1 0 obj << /Encrypt 2 0 R >>", StandardCharsets.ISO_8859_1);
            var scanner = new DefaultUploadSecurityScanner(UploadPolicy.defaults(), path -> false);

            var inspection = scanner.inspect(file, "secret.pdf", MediaType.APPLICATION_PDF);

            assertThat(inspection.detectedMediaType()).isEqualTo(MediaType.APPLICATION_PDF);
            assertThat(inspection.encrypted()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void detectsOoxmlArchiveBombFromExpandedBytes() throws Exception {
        var file = Files.createTempFile("bomb-", ".docx");
        try {
            Files.write(file, docxWithDocument(new byte[2048]));
            var scanner = new DefaultUploadSecurityScanner(
                    new UploadPolicy(10_000, 10, 1024, 1_000), path -> false);

            var inspection = scanner.inspect(file, "bomb.docx", docxMediaType());

            assertThat(inspection.detectedMediaType()).isEqualTo(docxMediaType());
            assertThat(inspection.archiveBomb()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsZipThatOnlyPretendsToBeDocx() throws Exception {
        var file = Files.createTempFile("fake-", ".docx");
        try {
            Files.write(file, zipOf(new Entry("word/fake.txt", "not opc".getBytes(StandardCharsets.UTF_8))));
            var scanner = new DefaultUploadSecurityScanner(UploadPolicy.defaults(), path -> false);

            var inspection = scanner.inspect(file, "fake.docx", docxMediaType());

            assertThat(inspection.detectedMediaType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsAmbiguousOoxmlPackage() throws Exception {
        var file = Files.createTempFile("ambiguous-", ".docx");
        try {
            Files.write(file, zipOf(
                    new Entry("[Content_Types].xml", ambiguousContentTypes().getBytes(StandardCharsets.UTF_8)),
                    new Entry("_rels/.rels", relationships("word/document.xml").getBytes(StandardCharsets.UTF_8)),
                    new Entry("word/document.xml", "word".getBytes(StandardCharsets.UTF_8)),
                    new Entry("xl/workbook.xml", "excel".getBytes(StandardCharsets.UTF_8))));
            var scanner = new DefaultUploadSecurityScanner(UploadPolicy.defaults(), path -> false);

            var inspection = scanner.inspect(file, "ambiguous.docx", docxMediaType());

            assertThat(inspection.detectedMediaType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsOpcMetadataThatOnlyContainsExpectedKeywords() throws Exception {
        var file = Files.createTempFile("keyword-fake-", ".docx");
        try {
            Files.write(file, zipOf(
                    new Entry("[Content_Types].xml", docxMainType().getBytes(StandardCharsets.UTF_8)),
                    new Entry("_rels/.rels", "officeDocument word/document.xml".getBytes(StandardCharsets.UTF_8)),
                    new Entry("word/document.xml", "not enough".getBytes(StandardCharsets.UTF_8))));
            var scanner = new DefaultUploadSecurityScanner(UploadPolicy.defaults(), path -> false);

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> scanner.inspect(file, "fake.docx", docxMediaType()))
                    .isInstanceOf(UploadRejectedException.class)
                    .hasMessageContaining("安全检查失败");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsWrongOpcNamespaceAndForgedRelationshipType() throws Exception {
        var wrongNamespace = Files.createTempFile("wrong-namespace-", ".docx");
        var forgedRelationship = Files.createTempFile("forged-relationship-", ".docx");
        try {
            Files.write(wrongNamespace, zipOf(
                    new Entry("[Content_Types].xml", contentTypes(
                            "https://attacker.invalid/content-types", docxMainType()).getBytes(StandardCharsets.UTF_8)),
                    new Entry("_rels/.rels", relationships("word/document.xml").getBytes(StandardCharsets.UTF_8)),
                    new Entry("word/document.xml", "document".getBytes(StandardCharsets.UTF_8))));
            Files.write(forgedRelationship, zipOf(
                    new Entry("[Content_Types].xml", docxContentTypes().getBytes(StandardCharsets.UTF_8)),
                    new Entry("_rels/.rels", relationships(
                            "word/document.xml", "https://attacker.invalid/officeDocument")
                            .getBytes(StandardCharsets.UTF_8)),
                    new Entry("word/document.xml", "document".getBytes(StandardCharsets.UTF_8))));
            var scanner = new DefaultUploadSecurityScanner(UploadPolicy.defaults(), path -> false);

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> scanner.inspect(wrongNamespace, "a.docx", docxMediaType()))
                    .isInstanceOf(UploadRejectedException.class);
            assertThat(scanner.inspect(forgedRelationship, "b.docx", docxMediaType()).detectedMediaType())
                    .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        } finally {
            Files.deleteIfExists(wrongNamespace);
            Files.deleteIfExists(forgedRelationship);
        }
    }

    @Test
    void rejectsNormalizedDuplicateCriticalEntries() throws Exception {
        var file = Files.createTempFile("duplicate-opc-", ".docx");
        try {
            Files.write(file, zipOf(
                    new Entry("[Content_Types].xml", docxContentTypes().getBytes(StandardCharsets.UTF_8)),
                    new Entry("./[Content_Types].xml", docxContentTypes().getBytes(StandardCharsets.UTF_8)),
                    new Entry("_rels/.rels", relationships("word/document.xml").getBytes(StandardCharsets.UTF_8)),
                    new Entry("word/document.xml", "document".getBytes(StandardCharsets.UTF_8))));
            var scanner = new DefaultUploadSecurityScanner(UploadPolicy.defaults(), path -> false);

            var inspection = scanner.inspect(file, "duplicate.docx", docxMediaType());

            assertThat(inspection.detectedMediaType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void detectsNestedArchiveBombWithSharedExpansionBudget() throws Exception {
        var nested = zipOf(new Entry("payload.txt", new byte[4096]));
        var file = Files.createTempFile("nested-bomb-", ".docx");
        try {
            Files.write(file, validDocx(new Entry("word/embeddings/evil.zip", nested)));
            var scanner = new DefaultUploadSecurityScanner(
                    new UploadPolicy(20_000, 100, 2048, 1_000), path -> false);

            var inspection = scanner.inspect(file, "nested.docx", docxMediaType());

            assertThat(inspection.archiveBomb()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void countsTrailingBytesAfterNestedZipEndRecord() throws Exception {
        var nested = zipOf(new Entry("small.txt", "small".getBytes(StandardCharsets.UTF_8)));
        var nestedWithTrailingBytes = java.util.Arrays.copyOf(nested, nested.length + 8192);
        var file = Files.createTempFile("nested-trailing-bomb-", ".docx");
        try {
            Files.write(file, validDocx(new Entry("word/embeddings/trailing.zip", nestedWithTrailingBytes)));
            var scanner = new DefaultUploadSecurityScanner(
                    new UploadPolicy(30_000, 100, 4096, 1_000), path -> false);

            var inspection = scanner.inspect(file, "nested.docx", docxMediaType());

            assertThat(inspection.archiveBomb()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void countsEntriesAcrossNestedArchives() throws Exception {
        var nested = zipOf(new Entry("one", new byte[]{1}), new Entry("two", new byte[]{2}));
        var file = Files.createTempFile("nested-entries-", ".docx");
        try {
            Files.write(file, validDocx(new Entry("word/embeddings/two.zip", nested)));
            var scanner = new DefaultUploadSecurityScanner(
                    new UploadPolicy(20_000, 5, 20_000, 1_000), path -> false);

            var inspection = scanner.inspect(file, "nested.docx", docxMediaType());

            assertThat(inspection.archiveBomb()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void checksCompressionRatioPerEntryInsteadOfWholePackage() throws Exception {
        var padding = new byte[8192];
        new java.util.Random(7).nextBytes(padding);
        var file = Files.createTempFile("ratio-bomb-", ".docx");
        try {
            Files.write(file, validDocx(
                    new Entry("word/document.xml", new byte[4096]),
                    new Entry("word/media/padding.bin", padding)));
            var scanner = new DefaultUploadSecurityScanner(
                    new UploadPolicy(50_000, 100, 50_000, 10), path -> false);

            var inspection = scanner.inspect(file, "ratio.docx", docxMediaType());

            assertThat(inspection.archiveBomb()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void invokesMalwareScannerAndReportsVerdict() throws Exception {
        var file = Files.createTempFile("image-", ".png");
        try {
            Files.write(file, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            var scanner = new DefaultUploadSecurityScanner(UploadPolicy.defaults(), path -> true);

            var inspection = scanner.inspect(file, "evil.png", MediaType.IMAGE_PNG);

            assertThat(inspection.detectedMediaType()).isEqualTo(MediaType.IMAGE_PNG);
            assertThat(inspection.malicious()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] docxWithDocument(byte[] content) throws Exception {
        return validDocx(new Entry("word/document.xml", content));
    }

    private static byte[] validDocx(Entry... additionalEntries) throws Exception {
        var entries = new java.util.ArrayList<Entry>();
        entries.add(new Entry("[Content_Types].xml", docxContentTypes().getBytes(StandardCharsets.UTF_8)));
        entries.add(new Entry("_rels/.rels", relationships("word/document.xml").getBytes(StandardCharsets.UTF_8)));
        var hasDocument = java.util.Arrays.stream(additionalEntries)
                .anyMatch(entry -> entry.name().equals("word/document.xml"));
        if (!hasDocument) {
            entries.add(new Entry("word/document.xml", "document".getBytes(StandardCharsets.UTF_8)));
        }
        entries.addAll(java.util.List.of(additionalEntries));
        return zipOf(entries.toArray(Entry[]::new));
    }

    private static byte[] zipOf(Entry... entries) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static String docxContentTypes() {
        return contentTypes("http://schemas.openxmlformats.org/package/2006/content-types", docxMainType());
    }

    private static String contentTypes(String namespace, String mainType) {
        return "<Types xmlns=\"" + namespace + "\">"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"" + mainType + "\"/></Types>";
    }

    private static String ambiguousContentTypes() {
        return "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"" + docxMainType() + "\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\""
                + "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>";
    }

    private static String relationships(String target) {
        return relationships(
                target,
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument");
    }

    private static String relationships(String target, String type) {
        return "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + type
                + "\" Target=\"" + target + "\"/></Relationships>";
    }

    private static String docxMainType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    }

    private static MediaType docxMediaType() {
        return MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private record Entry(String name, byte[] content) {
    }
}
