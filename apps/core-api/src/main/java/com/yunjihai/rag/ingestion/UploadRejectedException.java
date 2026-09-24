package com.yunjihai.rag.ingestion;

public final class UploadRejectedException extends RuntimeException {

    public UploadRejectedException(String message) {
        super(message);
    }
}
