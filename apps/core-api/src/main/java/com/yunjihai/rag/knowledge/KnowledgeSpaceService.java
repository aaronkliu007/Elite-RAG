package com.yunjihai.rag.knowledge;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class KnowledgeSpaceService {

    private final KnowledgeSpaceRepository repository;

    public KnowledgeSpaceService(KnowledgeSpaceRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional
    public KnowledgeSpace createSpace(String name, UUID actorId, Instant at) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("知识空间名称不能为空");
        }
        var space = new KnowledgeSpace(UUID.randomUUID(), name.strip(), actorId, at);
        repository.insert(space);
        return space;
    }
}
