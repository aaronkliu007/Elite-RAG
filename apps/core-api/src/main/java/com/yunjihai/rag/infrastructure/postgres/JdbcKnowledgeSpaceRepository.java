package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.KnowledgeSpace;
import com.yunjihai.rag.knowledge.KnowledgeSpaceRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public class JdbcKnowledgeSpaceRepository implements KnowledgeSpaceRepository {
    private final JdbcClient jdbc;

    public JdbcKnowledgeSpaceRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(KnowledgeSpace space) {
        Objects.requireNonNull(space);
        try {
            jdbc.sql("""
                    INSERT INTO knowledge_space (id, name, created_by, created_at)
                    VALUES (:id, :name, :createdBy, :createdAt)
                    """)
                    .param("id", space.id())
                    .param("name", space.name())
                    .param("createdBy", space.createdBy())
                    .param("createdAt", OffsetDateTime.ofInstant(space.createdAt(), ZoneOffset.UTC))
                    .update();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
