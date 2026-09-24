package com.yunjihai.rag.knowledge;

import java.util.UUID;

public interface DocumentContentGateway {

    DocumentContent readParsed(UUID documentId, UUID versionId);

    DocumentContent readSource(UUID documentId, UUID versionId);

    static DocumentContentGateway unavailable() {
        return new DocumentContentGateway() {
            @Override
            public DocumentContent readParsed(UUID documentId, UUID versionId) {
                throw new UnsupportedOperationException("解析内容存储尚未配置");
            }

            @Override
            public DocumentContent readSource(UUID documentId, UUID versionId) {
                throw new UnsupportedOperationException("源文件存储尚未配置");
            }
        };
    }
}
