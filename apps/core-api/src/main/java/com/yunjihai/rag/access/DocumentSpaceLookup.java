package com.yunjihai.rag.access;

import java.util.UUID;

@FunctionalInterface
public interface DocumentSpaceLookup {

    boolean belongsTo(UUID spaceId, UUID documentId);
}
