package com.yunjihai.rag.knowledge;

import java.util.Objects;

public record DocumentContent(String mediaType, String filename, byte[] bytes) {

    public DocumentContent {
        Objects.requireNonNull(mediaType);
        Objects.requireNonNull(filename);
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
