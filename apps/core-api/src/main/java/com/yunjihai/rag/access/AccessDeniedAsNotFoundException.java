package com.yunjihai.rag.access;

public final class AccessDeniedAsNotFoundException extends RuntimeException {

    public AccessDeniedAsNotFoundException() {
        super("Knowledge resource not found");
    }
}
