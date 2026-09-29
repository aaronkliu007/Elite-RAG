package com.yunjihai.rag.infrastructure.minio;

import io.minio.AbortMultipartUploadArgs;
import io.minio.CompleteMultipartUploadArgs;
import io.minio.CreateMultipartUploadArgs;
import io.minio.Http;
import io.minio.MinioAsyncClient;
import io.minio.StatObjectArgs;
import io.minio.UploadPartArgs;
import io.minio.Xml;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.errors.XmlParserException;
import io.minio.messages.CompleteMultipartUpload;
import io.minio.messages.CompleteMultipartUploadResult;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Part;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * MinIO Java 9.0.3 pinned adapter. The public SDK complete builder cannot carry arbitrary headers,
 * so this subclass uses the protected signed-request hook to put If-None-Match on the final POST.
 */
final class PinnedMinioMultipartClient extends MinioAsyncClient implements MinioMultipartOperations {

    PinnedMinioMultipartClient(MinioAsyncClient client) {
        super(client);
    }

    @Override
    public String begin(String bucket, String key, String contentType, String sha256) throws Exception {
        var headers = new Http.Headers(Map.of(
                "Content-Type", contentType,
                "x-amz-meta-sha256", sha256));
        var response = await(createMultipartUpload(CreateMultipartUploadArgs.builder()
                .bucket(bucket).object(key).headers(headers).build()));
        return response.result().uploadId();
    }

    @Override
    public UploadedPart uploadPart(
            String bucket, String key, String uploadId, int partNumber, byte[] bytes, int length) throws Exception {
        var response = await(uploadPart(UploadPartArgs.builder()
                .bucket(bucket).object(key).uploadId(uploadId).partNumber(partNumber)
                .data(bytes, length).build()));
        return new UploadedPart(response.part().partNumber(), response.part().etag());
    }

    @Override
    public void completeIfAbsent(
            String bucket, String key, String uploadId, List<UploadedPart> parts) throws Exception {
        Part[] sdkParts = parts.stream()
                .map(part -> new Part(part.number(), part.etag()))
                .toArray(Part[]::new);
        var args = CompleteMultipartUploadArgs.builder()
                .bucket(bucket).object(key).uploadId(uploadId).parts(sdkParts).build();
        await(completeWithCondition(args));
    }

    @Override
    public void abort(String bucket, String key, String uploadId) throws Exception {
        await(abortMultipartUpload(AbortMultipartUploadArgs.builder()
                .bucket(bucket).object(key).uploadId(uploadId).build()));
    }

    @Override
    public StoredObject stat(String bucket, String key) throws Exception {
        var response = await(statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()));
        return new StoredObject(
                response.size(), response.contentType(), response.userMetadata().getFirst("sha256"));
    }

    private CompletableFuture<Void> completeWithCondition(CompleteMultipartUploadArgs args) {
        final Http.Body body;
        try {
            body = new Http.Body(new CompleteMultipartUpload(args.parts()), null, null, null);
        } catch (MinioException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        var headers = new Http.Headers(Map.of("If-None-Match", "*"));
        var query = new Http.QueryParameters("uploadId", args.uploadId());
        return executePostAsync(args, headers, query, body).thenApply(response -> {
            try (response) {
                String xml = response.body() == null ? "" : response.body().string().trim();
                if (xml.isEmpty()) return null;
                try {
                    if (Xml.validate(ErrorResponse.class, xml)) {
                        var error = Xml.unmarshal(ErrorResponse.class, xml);
                        throw new CompletionException(new ErrorResponseException(error, response, null));
                    }
                } catch (XmlParserException notAnErrorDocument) {
                    // CompleteMultipartUpload success XML is intentionally not valid ErrorResponse XML.
                }
                Xml.unmarshal(CompleteMultipartUploadResult.class, xml);
                return null;
            } catch (CompletionException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("MINIO_SDK_OPERATION_FAILED", null);
        }
    }
}
