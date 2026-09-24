package com.yunjihai.rag.knowledge;

public final class ConcurrentDocumentUpdateException extends RuntimeException {

    public ConcurrentDocumentUpdateException() {
        super("文档已被其他操作更新，请刷新后重试");
    }
}
