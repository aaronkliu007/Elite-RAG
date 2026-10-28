package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.application.SectionEditVersionQueryPort;
import com.yunjihai.rag.solution.domain.SectionVersion;
import com.yunjihai.rag.solution.generation.SectionVersionRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcSectionEditVersionQuery implements SectionEditVersionQueryPort {
    private final JdbcClient jdbc;
    private final SectionVersionRepository sections;

    public JdbcSectionEditVersionQuery(JdbcClient jdbc, SectionVersionRepository sections) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.sections = Objects.requireNonNull(sections);
    }

    @Override public Optional<SectionVersion> latestEdit(UUID solutionId, UUID sectionId, UUID baselineId) {
        try {
            return jdbc.sql("""
                    SELECT parent.id FROM solution_section_version parent
                    WHERE parent.solution_id = :solution AND parent.section_id = :section
                      AND parent.baseline_section_version_id = :baseline AND parent.kind = 'HUMAN_EDIT'
                      AND NOT EXISTS (
                        SELECT 1 FROM solution_section_version child
                        WHERE child.parent_version_id = parent.id AND child.kind = 'HUMAN_EDIT')
                    """).param("solution", solutionId).param("section", sectionId)
                    .param("baseline", baselineId).query(UUID.class).optional().flatMap(sections::findById);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public List<UUID> listIds(UUID solutionId, UUID sectionId, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("invalid section history limit");
        try {
            return jdbc.sql("""
                    SELECT id FROM solution_section_version
                    WHERE solution_id = :solution AND section_id = :section
                      AND kind IN ('HUMAN_EDIT', 'ADOPTED')
                    ORDER BY created_at DESC, id DESC LIMIT :limit
                    """).param("solution", solutionId).param("section", sectionId)
                    .param("limit", limit).query(UUID.class).list();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }
}
