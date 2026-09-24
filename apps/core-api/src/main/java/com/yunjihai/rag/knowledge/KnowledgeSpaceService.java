package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.common.UuidV7;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class KnowledgeSpaceService {

    private final KnowledgeSpaceRepository repository;
    private final KnowledgeWriteAuthorization writeAuthorization;

    public KnowledgeSpaceService(
            KnowledgeSpaceRepository repository,
            KnowledgeWriteAuthorization writeAuthorization) {
        this.repository = Objects.requireNonNull(repository);
        this.writeAuthorization = Objects.requireNonNull(writeAuthorization);
    }

    @Transactional
    public KnowledgeSpace createSpace(String name, UUID actorId, Instant at) {
        writeAuthorization.require(actorId, null, null, KnowledgeWriteAuthorization.Action.CREATE_SPACE);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("知识空间名称不能为空");
        }
        var space = new KnowledgeSpace(UuidV7.generate(at), name.strip(), actorId, at);
        repository.insert(space);
        return space;
    }
}
