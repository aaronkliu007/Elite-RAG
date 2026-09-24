package com.yunjihai.rag.knowledge;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    void insert(DocumentAggregate document);

    Optional<DocumentAggregate> findById(UUID documentId);

    /**
     * 查找同一空间中仍然活跃且引用同一内容寻址 Blob 的文档。持久化实现必须以
     * 唯一约束保证该查询与新建操作在并发上传时仍具备幂等性。
     */
    Optional<DocumentAggregate> findActiveBySpaceAndSourceBlob(UUID spaceId, String sourceBlobId);

    void replace(DocumentAggregate document, long expectedRevision);
}
