package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.port.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.*;
import java.util.*;

public final class JdbcSolutionRepository implements SolutionRepository, SolutionQueryPort {
    private final JdbcClient jdbc;
    public JdbcSolutionRepository(JdbcClient jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void insert(Solution solution) {
        Objects.requireNonNull(solution);
        var s = solution.snapshot();
        try {
            jdbc.sql("""
                    INSERT INTO solution (id, space_id, name, created_by, created_at, solution_revision, revision,
                        current_requirement_version_id, current_outline_version_id, requirement_confirmed_by,
                        requirement_confirmed_at, outline_confirmed_by, outline_confirmed_at, phase, finalized_version_id)
                    VALUES (:id, :space, :name, :actor, :created, :solutionRevision, :revision, :requirement,
                        :outline, :requirementActor, :requirementAt, :outlineActor, :outlineAt, :phase, :finalized)
                    """).params(params(s)).update();
            persistAdopted(s);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public void replace(Solution solution, long expectedRevision) {
        Objects.requireNonNull(solution);
        if (expectedRevision < 0 || solution.revision() != expectedRevision + 1)
            throw new IllegalArgumentException("revision must advance exactly once");
        var s = solution.snapshot();
        var parameters = new HashMap<>(params(s));
        parameters.put("expected", expectedRevision);
        try {
            int updated = jdbc.sql("""
                    UPDATE solution SET name = :name, solution_revision = :solutionRevision, revision = :revision,
                        current_requirement_version_id = :requirement, current_outline_version_id = :outline,
                        requirement_confirmed_by = :requirementActor, requirement_confirmed_at = :requirementAt,
                        outline_confirmed_by = :outlineActor, outline_confirmed_at = :outlineAt,
                        phase = :phase, finalized_version_id = :finalized, updated_at = now()
                    WHERE id = :id AND space_id = :space AND revision = :expected
                    """).params(parameters).update();
            if (updated != 1) throw new ConcurrentSolutionUpdateException();
            jdbc.sql("DELETE FROM solution_adopted_section WHERE solution_id = :id")
                    .param("id", s.id()).update();
            persistAdopted(s);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public Optional<Solution> findById(UUID solutionId) {
        Objects.requireNonNull(solutionId);
        try {
            return jdbc.sql("SELECT * FROM solution WHERE id = :id")
                    .param("id", solutionId).query((row, ignored) -> Solution.restore(new SolutionSnapshot(
                            row.getObject("id", UUID.class), row.getObject("space_id", UUID.class),
                            row.getObject("created_by", UUID.class), row.getObject("created_at", OffsetDateTime.class).toInstant(),
                            SolutionName.of(row.getString("name")), row.getLong("solution_revision"), row.getLong("revision"),
                            row.getObject("current_requirement_version_id", UUID.class),
                            Optional.ofNullable(row.getObject("current_outline_version_id", UUID.class)),
                            confirmation(row.getObject("current_requirement_version_id", UUID.class),
                                    row.getObject("requirement_confirmed_by", UUID.class), row.getObject("requirement_confirmed_at", OffsetDateTime.class)),
                            confirmation(row.getObject("current_outline_version_id", UUID.class),
                                    row.getObject("outline_confirmed_by", UUID.class), row.getObject("outline_confirmed_at", OffsetDateTime.class)),
                            adopted(solutionId), SolutionPhase.valueOf(row.getString("phase")),
                            Optional.ofNullable(row.getObject("finalized_version_id", UUID.class))))).optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override public List<SolutionListItem> listBySpace(UUID spaceId, int page, int pageSize) {
        Objects.requireNonNull(spaceId);
        if (page < 0 || pageSize < 1 || pageSize > 100 || page > Integer.MAX_VALUE / pageSize)
            throw new IllegalArgumentException("invalid page bounds");
        try {
            return jdbc.sql("""
                    SELECT id, space_id, name, phase, revision, created_at, updated_at
                    FROM solution WHERE space_id = :space
                    ORDER BY updated_at DESC, id DESC OFFSET :offset LIMIT :limit
                    """).param("space", spaceId).param("offset", page * pageSize).param("limit", pageSize)
                    .query((row, ignored) -> new SolutionListItem(
                            row.getObject("id", UUID.class), row.getObject("space_id", UUID.class),
                            row.getString("name"), SolutionPhase.valueOf(row.getString("phase")),
                            row.getLong("revision"), row.getObject("created_at", OffsetDateTime.class).toInstant(),
                            row.getObject("updated_at", OffsetDateTime.class).toInstant())).list();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private Map<UUID, UUID> adopted(UUID solutionId) {
        var values = jdbc.sql("""
                SELECT section_id, section_version_id FROM solution_adopted_section WHERE solution_id = :id
                """).param("id", solutionId).query((row, ignored) -> Map.entry(
                        row.getObject("section_id", UUID.class), row.getObject("section_version_id", UUID.class))).list();
        Map<UUID, UUID> result = new HashMap<>();
        values.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    private void persistAdopted(SolutionSnapshot s) {
        s.adoptedSectionVersionIds().forEach((section, version) -> jdbc.sql("""
                INSERT INTO solution_adopted_section (solution_id, section_id, section_version_id)
                VALUES (:solution, :section, :version)
                """).param("solution", s.id()).param("section", section).param("version", version).update());
    }

    private static Optional<VersionConfirmation> confirmation(UUID version, UUID actor, OffsetDateTime at) {
        return actor == null ? Optional.empty() : Optional.of(new VersionConfirmation(version, actor, at.toInstant()));
    }

    private static Map<String, Object> params(SolutionSnapshot s) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", s.id()); result.put("space", s.spaceId()); result.put("name", s.name().value());
        result.put("actor", s.createdBy()); result.put("created", at(s.createdAt()));
        result.put("solutionRevision", s.solutionRevision()); result.put("revision", s.revision());
        result.put("requirement", s.currentRequirementVersionId());
        result.put("outline", s.currentOutlineVersionId().orElse(null));
        result.put("requirementActor", s.requirementConfirmation().map(VersionConfirmation::confirmedBy).orElse(null));
        result.put("requirementAt", s.requirementConfirmation().map(c -> at(c.confirmedAt())).orElse(null));
        result.put("outlineActor", s.outlineConfirmation().map(VersionConfirmation::confirmedBy).orElse(null));
        result.put("outlineAt", s.outlineConfirmation().map(c -> at(c.confirmedAt())).orElse(null));
        result.put("phase", s.phase().name()); result.put("finalized", s.finalizedVersionId().orElse(null));
        return result;
    }

    private static OffsetDateTime at(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
