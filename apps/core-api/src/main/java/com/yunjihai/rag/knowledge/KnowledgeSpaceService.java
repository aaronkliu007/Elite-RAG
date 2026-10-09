package com.yunjihai.rag.knowledge;

import com.yunjihai.rag.common.UuidV7;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class KnowledgeSpaceService {

    private final KnowledgeSpaceRepository repository;
    private final KnowledgeWriteAuthorization writeAuthorization;
    private final CreatorAccess creatorAccess;

    public KnowledgeSpaceService(
            KnowledgeSpaceRepository repository,
            KnowledgeWriteAuthorization writeAuthorization,
            CreatorAccess creatorAccess) {
        this.repository = Objects.requireNonNull(repository);
        this.writeAuthorization = Objects.requireNonNull(writeAuthorization);
        this.creatorAccess = Objects.requireNonNull(creatorAccess);
    }

    @Transactional
    public KnowledgeSpace createSpace(String name, UUID actorId, Instant at) {
        return createSpace(name, "", actorId, at);
    }

    @Transactional
    public KnowledgeSpace createSpace(String name, String description, UUID actorId, Instant at) {
        writeAuthorization.require(actorId, null, null, KnowledgeWriteAuthorization.Action.CREATE_SPACE);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("知识空间名称不能为空");
        }
        String normalizedName = name.strip();
        if (normalizedName.length() > 255) throw new IllegalArgumentException("知识空间名称不能超过 255 个字符");
        String normalizedDescription = description == null ? "" : description.strip();
        if (normalizedDescription.length() > 2000) throw new IllegalArgumentException("知识空间说明不能超过 2000 个字符");
        var space = new KnowledgeSpace(
                UuidV7.generate(at), normalizedName, normalizedDescription, 0, actorId, at);
        repository.insert(space);
        creatorAccess.grantSpaceAdministrator(space.id(), actorId, at);
        return space;
    }

    @Transactional(readOnly = true)
    public List<KnowledgeSpace> listAuthorized(UUID userId, int page, int pageSize) {
        Objects.requireNonNull(userId);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("page must be >= 1 and pageSize must be between 1 and 100");
        }
        long offset = (long) (page - 1) * pageSize;
        if (offset > Integer.MAX_VALUE) throw new IllegalArgumentException("page is too large");
        return List.copyOf(repository.listAuthorized(userId, (int) offset, pageSize));
    }

    @FunctionalInterface
    public interface CreatorAccess {
        void grantSpaceAdministrator(UUID spaceId, UUID creatorId, Instant at);
    }
}
