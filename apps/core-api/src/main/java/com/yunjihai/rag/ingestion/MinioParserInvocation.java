package com.yunjihai.rag.ingestion;

import com.yunjihai.rag.infrastructure.minio.MinioBlobReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 把对象存储内容隔离到本地临时文件后调用无状态解析服务。 */
public final class MinioParserInvocation implements ParserInvocation {

    private static final long MAX_SOURCE_BYTES = UploadPolicy.defaults().maxBytes();

    @FunctionalInterface
    interface SourceBlobReader {
        byte[] read(String objectKey, long maxBytes);
    }

    private final SourceBlobReader blobs;
    private final ParserClient parser;
    private final Path temporaryDirectory;
    private final int timeoutSeconds;

    public MinioParserInvocation(
            MinioBlobReader blobs,
            ParserClient parser,
            Path temporaryDirectory,
            int timeoutSeconds) {
        this(blobs::read, parser, temporaryDirectory, timeoutSeconds);
    }

    MinioParserInvocation(
            SourceBlobReader blobs,
            ParserClient parser,
            Path temporaryDirectory,
            int timeoutSeconds) {
        this.blobs = Objects.requireNonNull(blobs);
        this.parser = Objects.requireNonNull(parser);
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory)
                .toAbsolutePath().normalize();
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("解析超时参数无效");
        }
        this.timeoutSeconds = timeoutSeconds;
        try {
            Files.createDirectories(this.temporaryDirectory);
            if (!Files.isDirectory(this.temporaryDirectory)) {
                throw new IllegalStateException("PARSER_TEMP_DIRECTORY_INVALID", null);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("PARSER_TEMP_DIRECTORY_INVALID", null);
        }
    }

    @Override
    public CanonicalDocumentV1 parse(IngestionLease lease) {
        Objects.requireNonNull(lease);
        var request = lease.request();
        var bytes = blobs.read(request.sourceBlobId(), MAX_SOURCE_BYTES);
        if (bytes.length != request.sizeBytes()) {
            throw new IllegalStateException("MINIO_OBJECT_LENGTH_MISMATCH", null);
        }
        Path sourceFile = null;
        RuntimeException primaryFailure = null;
        try {
            sourceFile = Files.createTempFile(temporaryDirectory, "parser-source-", ".upload");
            Files.write(sourceFile, bytes);
            return parser.parse(new ParserJob(
                    "parser-job-v1",
                    lease.jobId(),
                    request.filename(),
                    request.mediaType(),
                    request.sizeBytes(),
                    request.sha256(),
                    timeoutSeconds), sourceFile);
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } catch (IOException failure) {
            primaryFailure = new IllegalStateException("PARSER_TEMP_FILE_FAILED", null);
            throw primaryFailure;
        } finally {
            if (sourceFile != null) {
                try {
                    Files.deleteIfExists(sourceFile);
                } catch (IOException | SecurityException failure) {
                    var cleanupFailure = new IllegalStateException("PARSER_TEMP_FILE_DELETE_FAILED", null);
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }
}
