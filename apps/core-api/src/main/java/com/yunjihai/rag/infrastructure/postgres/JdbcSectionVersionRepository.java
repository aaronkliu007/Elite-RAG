package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.generation.*;
import com.yunjihai.rag.solution.verification.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class JdbcSectionVersionRepository implements SectionVersionRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcSectionVersionRepository(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    @Override
    public Optional<SectionVersion> findById(UUID sectionVersionId) {
        Objects.requireNonNull(sectionVersionId);
        try {
            return jdbc.sql("SELECT * FROM solution_section_version WHERE id = :id")
                    .param("id", sectionVersionId).query((row, ignored) -> {
                        var claims = claims(sectionVersionId);
                        var evidenceIds = new HashSet<UUID>();
                        claims.forEach(claim -> evidenceIds.addAll(claim.evidenceIds()));
                        return new SectionVersion(
                                row.getObject("id", UUID.class), row.getObject("solution_id", UUID.class),
                                row.getObject("section_id", UUID.class),
                                SectionVersionKind.valueOf(row.getString("kind")), row.getString("content"),
                                claims, Set.copyOf(evidenceIds),
                                row.getObject("requirement_version_id", UUID.class),
                                row.getObject("outline_version_id", UUID.class),
                                Optional.ofNullable(row.getObject("baseline_section_version_id", UUID.class)),
                                Optional.ofNullable(row.getObject("parent_version_id", UUID.class)),
                                Optional.ofNullable(row.getObject("created_by", UUID.class)),
                                Optional.ofNullable(row.getString("model_id")),
                                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                                new SolutionRiskReport(verifications(sectionVersionId)));
                    }).optional();
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public Optional<SectionVersion> findByAttemptId(UUID attemptId) {
        Objects.requireNonNull(attemptId);
        try {
            return jdbc.sql("SELECT id FROM solution_section_version WHERE attempt_id = :attempt")
                    .param("attempt", attemptId).query(UUID.class).optional().flatMap(this::findById);
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    @Override
    public SectionVersion insertIfAbsent(SectionVersion sectionVersion) {
        Objects.requireNonNull(sectionVersion);
        if (sectionVersion.kind() == SectionVersionKind.ADOPTED
                && !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new GenerationStateException();
        }
        return transactions.execute(status -> {
            if (sectionVersion.kind() == SectionVersionKind.MODEL_DRAFT) {
                var existing = findById(sectionVersion.id()).orElseThrow(GenerationStateException::new);
                if (!existing.equals(normalized(sectionVersion))) throw new GenerationStateException();
                return sectionVersion;
            }
            if (insert(sectionVersion, null)) return sectionVersion;
            var existing = findById(sectionVersion.id()).orElseThrow(GenerationStateException::new);
            if (!existing.equals(normalized(sectionVersion))) throw new GenerationStateException();
            return sectionVersion;
        });
    }

    void insertDraft(UUID attemptId, SectionVersion sectionVersion) {
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(sectionVersion);
        if (sectionVersion.kind() != SectionVersionKind.MODEL_DRAFT || !insert(sectionVersion, attemptId)) {
            throw new GenerationStateException();
        }
    }

    private boolean insert(SectionVersion version, UUID attemptId) {
        try {
            var inserted = jdbc.sql("""
                    INSERT INTO solution_section_version
                        (id, solution_id, section_id, kind, content, requirement_version_id,
                         outline_version_id, baseline_section_version_id, parent_version_id,
                         created_by, model_id, created_at, attempt_id)
                    VALUES (:id, :solutionId, :sectionId, :kind, :content, :requirementId,
                            :outlineId, :baselineId, :parentId, :createdBy, :modelId, :createdAt, :attemptId)
                    ON CONFLICT (id) DO NOTHING RETURNING id
                    """).param("id", version.id()).param("solutionId", version.solutionId())
                    .param("sectionId", version.sectionId()).param("kind", version.kind().name())
                    .param("content", version.content()).param("requirementId", version.requirementVersionId())
                    .param("outlineId", version.outlineVersionId())
                    .param("baselineId", version.baselineSectionVersionId().orElse(null))
                    .param("parentId", version.parentVersionId().orElse(null))
                    .param("createdBy", version.createdBy().orElse(null))
                    .param("modelId", version.modelId().orElse(null))
                    .param("createdAt", at(version.createdAt().truncatedTo(ChronoUnit.MICROS)))
                    .param("attemptId", attemptId)
                    .query(UUID.class).optional();
            if (inserted.isEmpty()) return false;
            var verificationPositions = new HashMap<UUID, Integer>();
            var verificationById = new HashMap<UUID, ClaimVerification>();
            for (int index = 0; index < version.verification().verifications().size(); index++) {
                var verification = version.verification().verifications().get(index);
                verificationPositions.put(verification.claimId(), index + 1);
                verificationById.put(verification.claimId(), verification);
            }
            for (int index = 0; index < version.claims().size(); index++) {
                var claim = version.claims().get(index);
                var verification = verificationById.get(claim.claimId());
                jdbc.sql("""
                        INSERT INTO solution_section_claim
                            (version_id, claim_id, claim_order, verification_order, body, factual, verdict, reason_code)
                        VALUES (:versionId, :claimId, :claimOrder, :verificationOrder,
                                :body, :factual, :verdict, :reasonCode)
                        """).param("versionId", version.id()).param("claimId", claim.claimId())
                        .param("claimOrder", index + 1)
                        .param("verificationOrder", verificationPositions.get(claim.claimId()))
                        .param("body", claim.text()).param("factual", claim.factual())
                        .param("verdict", verification.verdict().name())
                        .param("reasonCode", verification.reasonCode()).update();
                for (var evidenceId : claim.evidenceIds()) {
                    jdbc.sql("""
                            INSERT INTO solution_section_claim_evidence (version_id, claim_id, evidence_id)
                            VALUES (:versionId, :claimId, :evidenceId)
                            """).param("versionId", version.id()).param("claimId", claim.claimId())
                            .param("evidenceId", evidenceId).update();
                }
                for (var risk : claim.riskTypes()) {
                    jdbc.sql("""
                            INSERT INTO solution_section_claim_risk (version_id, claim_id, risk_type)
                            VALUES (:versionId, :claimId, :riskType)
                            """).param("versionId", version.id()).param("claimId", claim.claimId())
                            .param("riskType", risk.name()).update();
                }
            }
            return true;
        } catch (DataAccessException failure) {
            throw PostgresPersistenceExceptionTranslator.translate(failure);
        }
    }

    private List<Claim> claims(UUID versionId) {
        return jdbc.sql("""
                SELECT claim_id, body, factual FROM solution_section_claim
                WHERE version_id = :versionId ORDER BY claim_order
                """).param("versionId", versionId).query((row, ignored) -> {
                    var claimId = row.getObject("claim_id", UUID.class);
                    var evidence = jdbc.sql("""
                            SELECT evidence_id FROM solution_section_claim_evidence
                            WHERE version_id = :versionId AND claim_id = :claimId
                            """).param("versionId", versionId).param("claimId", claimId)
                            .query(UUID.class).list();
                    var risks = jdbc.sql("""
                            SELECT risk_type FROM solution_section_claim_risk
                            WHERE version_id = :versionId AND claim_id = :claimId
                            """).param("versionId", versionId).param("claimId", claimId)
                            .query(String.class).list();
                    var riskTypes = new HashSet<RiskType>();
                    risks.forEach(value -> riskTypes.add(RiskType.valueOf(value)));
                    return new Claim(claimId, row.getString("body"), row.getBoolean("factual"),
                            Set.copyOf(evidence), Set.copyOf(riskTypes));
                }).list();
    }

    private List<ClaimVerification> verifications(UUID versionId) {
        return jdbc.sql("""
                SELECT claim_id, verdict, reason_code FROM solution_section_claim
                WHERE version_id = :versionId ORDER BY verification_order
                """).param("versionId", versionId).query((row, ignored) -> new ClaimVerification(
                        row.getObject("claim_id", UUID.class), CitationVerdict.valueOf(row.getString("verdict")),
                        row.getString("reason_code"))).list();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static SectionVersion normalized(SectionVersion version) {
        return new SectionVersion(version.id(), version.solutionId(), version.sectionId(),
                version.kind(), version.content(), version.claims(), version.evidenceIds(),
                version.requirementVersionId(), version.outlineVersionId(),
                version.baselineSectionVersionId(), version.parentVersionId(),
                version.createdBy(), version.modelId(),
                version.createdAt().truncatedTo(ChronoUnit.MICROS), version.verification());
    }
}
