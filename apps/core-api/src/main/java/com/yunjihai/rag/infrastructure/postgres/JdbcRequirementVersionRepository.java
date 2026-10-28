package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.port.RequirementVersionRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

public final class JdbcRequirementVersionRepository implements RequirementVersionRepository {
    private final JdbcClient jdbc;
    public JdbcRequirementVersionRepository(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void insert(RequirementVersion version) {
        throw new IllegalArgumentException("solution id is required");
    }

    @Override public void insert(UUID solutionId, RequirementVersion version) {
        Objects.requireNonNull(solutionId);
        Objects.requireNonNull(version);
        try {
            jdbc.sql("""
                    INSERT INTO solution_requirement_version (id, solution_id, number, created_by, created_at, change_reason)
                    VALUES (:id, :solution, :number, :actor, :at, :reason)
                    """).param("id", version.id()).param("solution", solutionId).param("number", version.number())
                    .param("actor", version.createdBy()).param("at", at(version.createdAt()))
                    .param("reason", version.changeReason()).update();
            int order = 0;
            for (var item : version.items()) {
                jdbc.sql("""
                        INSERT INTO solution_requirement_item (version_id, item_id, item_order, category, body)
                        VALUES (:version, :id, :position, :category, :body)
                        """).param("version", version.id()).param("id", item.itemId())
                        .param("position", ++order).param("category", item.category().name())
                        .param("body", item.text()).update();
                int sourceOrder = 0;
                for (var source : item.sources()) {
                    jdbc.sql("""
                            INSERT INTO solution_requirement_source
                                (version_id, item_id, source_order, space_id, document_id, document_version_id, locator)
                            SELECT :version, :item, :position, solution.space_id, :document, :documentVersion,
                                   CAST(:locator AS jsonb)
                              FROM solution WHERE solution.id = :solution
                            """).param("version", version.id()).param("item", item.itemId())
                            .param("position", ++sourceOrder).param("solution", solutionId)
                            .param("document", source.documentVersion().documentId())
                            .param("documentVersion", source.documentVersion().versionId())
                            .param("locator", SolutionLocatorJson.write(source.locator())).update();
                }
            }
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public Optional<RequirementVersion> findById(UUID versionId) {
        Objects.requireNonNull(versionId);
        try {
            return jdbc.sql("""
                    SELECT id, number, created_by, created_at, change_reason
                    FROM solution_requirement_version WHERE id = :id
                    """).param("id", versionId).query((row, ignored) -> new RequirementVersion(
                            row.getObject("id", UUID.class), row.getLong("number"), items(versionId),
                            row.getObject("created_by", UUID.class), row.getObject("created_at", OffsetDateTime.class).toInstant(),
                            row.getString("change_reason"))).optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private List<RequirementItem> items(UUID versionId) {
        return jdbc.sql("""
                SELECT item_id, category, body FROM solution_requirement_item
                WHERE version_id = :version ORDER BY item_order
                """).param("version", versionId).query((row, ignored) -> new RequirementItem(
                        row.getObject("item_id", UUID.class), RequirementCategory.valueOf(row.getString("category")),
                        row.getString("body"), sources(versionId, row.getObject("item_id", UUID.class)))).list();
    }

    private List<RequirementSource> sources(UUID versionId, UUID itemId) {
        return jdbc.sql("""
                SELECT document_id, document_version_id, locator::text AS locator
                FROM solution_requirement_source WHERE version_id = :version AND item_id = :item ORDER BY source_order
                """).param("version", versionId).param("item", itemId).query((row, ignored) -> new RequirementSource(
                        new DocumentVersionRef(row.getObject("document_id", UUID.class),
                                row.getObject("document_version_id", UUID.class)),
                        SolutionLocatorJson.read(row.getString("locator")))).list();
    }

    private static OffsetDateTime at(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
