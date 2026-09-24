package com.yunjihai.rag.ingestion;

import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

public final class DefaultUploadSecurityScanner implements UploadSecurityScanner {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final MediaType PPTX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final String DOCX_MAIN =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final String XLSX_MAIN =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
    private static final String PPTX_MAIN =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
    private static final int MAX_NESTED_ARCHIVE_DEPTH = 3;
    private static final int MAX_OPC_METADATA_BYTES = 1024 * 1024;
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
        if (isZipHeader(header)) {
            return inspectOoxml(file);
        }
        if (StreamingUtf8Inspector.isText(file)) {
            return new Detection(MediaType.TEXT_MARKDOWN, false, false, "utf-8 text");
        }
        return new Detection(MediaType.APPLICATION_OCTET_STREAM, false, false, "unknown");
    }

    private Detection inspectOoxml(Path file) throws IOException {
        var budget = new ArchiveBudget(policy);
        var packageInfo = new OpcPackageInfo();
        try (var source = Files.newInputStream(file)) {
            inspectZip(source, 0, budget, packageInfo, true);
        }
        return new Detection(
                packageInfo.detectMediaType(), false, budget.bomb,
                "archive entries=" + budget.entries + ", expanded=" + budget.expandedBytes);
    }

    private void inspectZip(
            InputStream source,
            int depth,
            ArchiveBudget budget,
            OpcPackageInfo packageInfo,
            boolean capturePackageMetadata) throws IOException {
        if (depth > MAX_NESTED_ARCHIVE_DEPTH) {
            budget.bomb = true;
            return;
        }
        try (var zip = new ZipInputStream(new NonClosingInputStream(source))) {
            var entry = zip.getNextEntry();
            while (entry != null && !budget.bomb) {
                budget.addEntry();
                var name = entry.getName();
                if (capturePackageMetadata) {
                    packageInfo.observeEntry(name);
                }
                var entryExpanded = inspectEntry(
                        zip, depth, budget, packageInfo, capturePackageMetadata, name);
                if (budget.bomb) {
                    return;
                }
                budget.checkEntryRatio(entryExpanded, entry.getCompressedSize());
                if (budget.bomb) {
                    return;
                }
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }
    }

    private long inspectEntry(
            ZipInputStream zip,
            int depth,
            ArchiveBudget budget,
            OpcPackageInfo packageInfo,
            boolean capturePackageMetadata,
            String name) throws IOException {
        if (capturePackageMetadata && (name.equals("[Content_Types].xml") || name.equals("_rels/.rels"))) {
            var metadata = readMetadata(zip, budget);
            packageInfo.observeMetadata(name, metadata);
            return metadata.length;
        }

        var prefix = zip.readNBytes(4);
        budget.addExpanded(prefix.length);
        if (isZipHeader(prefix)) {
            if (depth == MAX_NESTED_ARCHIVE_DEPTH) {
                budget.bomb = true;
                return prefix.length;
            }
            var nestedSource = new SequenceInputStream(
                    new ByteArrayInputStream(prefix), new NonClosingInputStream(zip));
            var countingSource = new CountingInputStream(nestedSource);
            inspectZip(countingSource, depth + 1, budget, packageInfo, false);
            budget.addExpanded(Math.max(0, countingSource.count() - prefix.length));
            return countingSource.count();
        }

        long expanded = prefix.length;
        var buffer = new byte[8192];
        int read;
        while (!budget.bomb && (read = zip.read(buffer)) != -1) {
            expanded += read;
            budget.addExpanded(read);
        }
        return expanded;
    }

    private static byte[] readMetadata(ZipInputStream zip, ArchiveBudget budget) throws IOException {
        var output = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        int read;
        while (!budget.bomb && (read = zip.read(buffer)) != -1) {
            budget.addExpanded(read);
            if (output.size() + read > MAX_OPC_METADATA_BYTES) {
                budget.bomb = true;
                break;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] readHeader(Path file, int length) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return input.readNBytes(length);
        }
    }

    private static boolean isZipHeader(byte[] header) {
        return header.length >= 4 && header[0] == 'P' && header[1] == 'K'
                && ((header[2] == 3 && header[3] == 4)
                || (header[2] == 5 && header[3] == 6)
                || (header[2] == 7 && header[3] == 8));
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

    private static long safeMultiply(long value, int factor) {
        try {
            return Math.multiplyExact(value, factor);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static final class ArchiveBudget {
        private final UploadPolicy policy;
        private int entries;
        private long expandedBytes;
        private boolean bomb;

        private ArchiveBudget(UploadPolicy policy) {
            this.policy = policy;
        }

        private void addEntry() {
            entries++;
            bomb |= entries > policy.maxArchiveEntries();
        }

        private void addExpanded(long bytes) {
            if (bytes > Long.MAX_VALUE - expandedBytes) {
                bomb = true;
                return;
            }
            expandedBytes += bytes;
            bomb |= expandedBytes > policy.maxExpandedBytes();
        }

        private void checkEntryRatio(long expanded, long compressed) {
            if (compressed > 0) {
                bomb |= expanded > safeMultiply(compressed, policy.maxCompressionRatio());
            }
        }
    }

    private static final class OpcPackageInfo {
        private boolean contentTypesPresent;
        private boolean relationshipsPresent;
        private boolean wordMainPresent;
        private boolean excelMainPresent;
        private boolean powerPointMainPresent;
        private final Map<String, String> contentTypes = new HashMap<>();
        private final Set<String> officeDocumentTargets = new HashSet<>();

        private void observeEntry(String name) {
            contentTypesPresent |= name.equals("[Content_Types].xml");
            relationshipsPresent |= name.equals("_rels/.rels");
            wordMainPresent |= name.equals("word/document.xml");
            excelMainPresent |= name.equals("xl/workbook.xml");
            powerPointMainPresent |= name.equals("ppt/presentation.xml");
        }

        private void observeMetadata(String name, byte[] value) throws IOException {
            var document = parseXml(value);
            if (name.equals("[Content_Types].xml")) {
                if (!"Types".equals(document.getDocumentElement().getLocalName())) {
                    throw new IOException("OPC Content Types 根节点无效");
                }
                var overrides = document.getElementsByTagNameNS("*", "Override");
                for (var index = 0; index < overrides.getLength(); index++) {
                    var element = overrides.item(index);
                    var attributes = element.getAttributes();
                    var partName = attributes.getNamedItem("PartName");
                    var contentType = attributes.getNamedItem("ContentType");
                    if (partName != null && contentType != null) {
                        contentTypes.put(partName.getNodeValue(), contentType.getNodeValue());
                    }
                }
            } else if (name.equals("_rels/.rels")) {
                if (!"Relationships".equals(document.getDocumentElement().getLocalName())) {
                    throw new IOException("OPC Relationships 根节点无效");
                }
                var relationships = document.getElementsByTagNameNS("*", "Relationship");
                for (var index = 0; index < relationships.getLength(); index++) {
                    var element = relationships.item(index);
                    var attributes = element.getAttributes();
                    var type = attributes.getNamedItem("Type");
                    var target = attributes.getNamedItem("Target");
                    if (type != null && target != null && type.getNodeValue().endsWith("/officeDocument")) {
                        officeDocumentTargets.add(normalizeTarget(target.getNodeValue()));
                    }
                }
            }
        }

        private MediaType detectMediaType() {
            if (!contentTypesPresent || !relationshipsPresent || officeDocumentTargets.isEmpty()) {
                return MediaType.APPLICATION_OCTET_STREAM;
            }
            var presentMainParts = (wordMainPresent ? 1 : 0)
                    + (excelMainPresent ? 1 : 0)
                    + (powerPointMainPresent ? 1 : 0);
            if (presentMainParts != 1) {
                return MediaType.APPLICATION_OCTET_STREAM;
            }
            var matches = 0;
            MediaType detected = MediaType.APPLICATION_OCTET_STREAM;
            if (wordMainPresent && DOCX_MAIN.equals(contentTypes.get("/word/document.xml"))
                    && officeDocumentTargets.contains("word/document.xml")) {
                matches++;
                detected = DOCX;
            }
            if (excelMainPresent && XLSX_MAIN.equals(contentTypes.get("/xl/workbook.xml"))
                    && officeDocumentTargets.contains("xl/workbook.xml")) {
                matches++;
                detected = XLSX;
            }
            if (powerPointMainPresent && PPTX_MAIN.equals(contentTypes.get("/ppt/presentation.xml"))
                    && officeDocumentTargets.contains("ppt/presentation.xml")) {
                matches++;
                detected = PPTX;
            }
            return matches == 1 ? detected : MediaType.APPLICATION_OCTET_STREAM;
        }

        private static Document parseXml(byte[] value) throws IOException {
            try {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var builder = factory.newDocumentBuilder();
                builder.setErrorHandler(new DefaultHandler() {
                    @Override
                    public void error(SAXParseException exception) throws SAXException {
                        throw exception;
                    }

                    @Override
                    public void fatalError(SAXParseException exception) throws SAXException {
                        throw exception;
                    }
                });
                return builder.parse(new ByteArrayInputStream(value));
            } catch (javax.xml.parsers.ParserConfigurationException | SAXException exception) {
                throw new IOException("OPC 元数据 XML 无效", exception);
            }
        }

        private static String normalizeTarget(String target) {
            var normalized = target.replace('\\', '/');
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            return normalized;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        private CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            var value = super.read();
            if (value != -1) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        private long count() {
            return count;
        }
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        private NonClosingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
            // 嵌套 ZIP 不能关闭父条目流。
        }
    }

    private record Detection(MediaType mediaType, boolean encrypted, boolean archiveBomb, String detail) {
    }
}
