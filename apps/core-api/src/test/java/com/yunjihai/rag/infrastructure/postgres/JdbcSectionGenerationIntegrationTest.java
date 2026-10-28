package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.generation.*;
import com.yunjihai.rag.solution.verification.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class JdbcSectionGenerationIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_section_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static GenerationAttemptStore attempts;
    private static SectionVersionRepository sections;
    private static SectionGenerationCommitPort commits;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_section_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        jdbc = JdbcClient.create(dataSource);
        attempts = new JdbcGenerationAttemptStore(jdbc);
        var manager = new DataSourceTransactionManager(dataSource);
        var repository = new JdbcSectionVersionRepository(jdbc, manager);
        sections = repository;
        commits = new JdbcSectionGenerationCommitPort(jdbc, manager, repository);
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void commitsVerifiedDraftAndAttemptAsOneRecoverableCheckpoint() {
        var fixture = seed(100);
        var draft = draft(fixture, id(110));
        var succeeded = fixture.verifying().succeed(draft.id(), NOW.plusSeconds(5));

        assertThat(commits.commitIfCurrent(fixture.key(), fixture.verifying().id(), draft,
                succeeded, fixture.verifying().revision())).isTrue();
        assertThat(new JdbcGenerationAttemptStore(jdbc).findByClientKey(fixture.key().clientKey()))
                .contains(succeeded);
        assertThat(sections.findById(draft.id())).contains(draft);
        assertThat(sections.findByAttemptId(fixture.verifying().id())).contains(draft);
        assertThat(sections.insertIfAbsent(draft)).isEqualTo(draft);
    }

    @Test void evidenceSnapshotSurvivesDraftCommitAndRejectsChangedSource() {
        var fixture = seed(1900);
        var store = new JdbcGenerationEvidenceStore(jdbc,
                new DataSourceTransactionManager(dataSource));
        var draft = draft(fixture, id(1910));
        var source = new GenerationEvidenceSource(1, draft.evidenceIds().iterator().next(),
                fixture.key().solutionId(), fixture.key().solutionId(),
                fixture.key().requirementVersionId(), "node-1", "a".repeat(64));
        store.saveSnapshot(fixture.verifying().id(), List.of(source));
        store.saveSnapshot(fixture.verifying().id(), List.of(source));
        assertThat(store.findByAttemptId(fixture.verifying().id())).containsExactly(source);

        var succeeded = fixture.verifying().succeed(draft.id(), NOW.plusSeconds(5));
        assertThat(commits.commitIfCurrent(fixture.key(), fixture.verifying().id(), draft,
                succeeded, fixture.verifying().revision())).isTrue();
        assertThat(store.findBySectionVersionId(draft.id())).containsExactly(source);
        assertThatThrownBy(() -> store.saveSnapshot(fixture.verifying().id(),
                List.of(new GenerationEvidenceSource(1, source.evidenceId(), source.spaceId(),
                        source.documentId(), source.versionId(), "node-2", source.excerptHash()))))
                .isInstanceOf(GenerationRequestConflictException.class);
    }

    @Test void staleBaselineRejectsBothWrites() {
        var fixture = seed(200);
        jdbc.sql("""
                INSERT INTO solution_adopted_section (solution_id, section_id, section_version_id)
                VALUES (:solutionId, :sectionId, :versionId)
                """).param("solutionId", fixture.key().solutionId())
                .param("sectionId", fixture.key().sectionId()).param("versionId", id(299)).update();
        var draft = draft(fixture, id(210));
        var succeeded = fixture.verifying().succeed(draft.id(), NOW.plusSeconds(5));

        assertThat(commits.commitIfCurrent(fixture.key(), fixture.verifying().id(), draft,
                succeeded, fixture.verifying().revision())).isFalse();
        assertThat(sections.findById(draft.id())).isEmpty();
        assertThat(attempts.findByClientKey(fixture.key().clientKey())).contains(fixture.verifying());
    }

    @Test void staleAttemptRevisionRejectsBothWrites() {
        var fixture = seed(300);
        var draft = draft(fixture, id(310));
        var succeeded = fixture.verifying().succeed(draft.id(), NOW.plusSeconds(5));
        var failed = fixture.verifying().failRetryable("WORKER_INTERRUPTED", NOW.plusSeconds(5));
        attempts.replace(failed, fixture.verifying().revision());

        assertThat(commits.commitIfCurrent(fixture.key(), fixture.verifying().id(), draft,
                succeeded, fixture.verifying().revision())).isFalse();
        assertThat(sections.findById(draft.id())).isEmpty();
        assertThat(attempts.findByClientKey(fixture.key().clientKey())).contains(failed);
    }

    @Test void outerTransactionRollbackRemovesDraftAndSuccessState() {
        var fixture = seed(400);
        var draft = draft(fixture, id(410));
        var succeeded = fixture.verifying().succeed(draft.id(), NOW.plusSeconds(5));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(commits.commitIfCurrent(fixture.key(), fixture.verifying().id(), draft,
                    succeeded, fixture.verifying().revision())).isTrue();
            throw new IllegalStateException("rollback after successful nested commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(sections.findById(draft.id())).isEmpty();
        assertThat(attempts.findByClientKey(fixture.key().clientKey())).contains(fixture.verifying());
    }

    @Test void sectionVersionCannotClaimAnUnrelatedOutlineSection() {
        var fixture = seed(500);
        var invalid = new SectionVersion(id(510), fixture.key().solutionId(), id(599),
                SectionVersionKind.HUMAN_EDIT, "人工草稿", List.of(), Set.of(),
                fixture.key().requirementVersionId(), fixture.key().outlineVersionId(),
                Optional.empty(), Optional.empty(), Optional.of(id(500)), Optional.empty(),
                NOW.plusSeconds(5), new SolutionRiskReport(List.of()));

        assertThatThrownBy(() -> sections.insertIfAbsent(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(sections.findById(invalid.id())).isEmpty();
    }

    @Test void sectionVersionCannotReferenceAnotherSolutionsParentOrBaseline() {
        var foreign = seed(600);
        var local = seed(700);
        var foreignDraft = draft(foreign, id(610));
        assertThat(commits.commitIfCurrent(foreign.key(), foreign.verifying().id(), foreignDraft,
                foreign.verifying().succeed(foreignDraft.id(), NOW.plusSeconds(5)),
                foreign.verifying().revision())).isTrue();
        var parentFromForeignSolution = humanVersion(local, id(710), Optional.empty(),
                Optional.of(foreignDraft.id()));
        var baselineFromForeignSolution = humanVersion(local, id(711), Optional.of(foreignDraft.id()),
                Optional.empty());

        assertThatThrownBy(() -> sections.insertIfAbsent(parentFromForeignSolution))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sections.insertIfAbsent(baselineFromForeignSolution))
                .isInstanceOf(RuntimeException.class);
        assertThat(sections.findById(parentFromForeignSolution.id())).isEmpty();
        assertThat(sections.findById(baselineFromForeignSolution.id())).isEmpty();
    }

    @Test void sectionVersionCannotReferenceAnotherSectionsParentOrBaseline() {
        var fixture = seed(800);
        var sibling = new SectionVersion(id(812), fixture.key().solutionId(), id(811),
                SectionVersionKind.HUMAN_EDIT, "相邻章节", List.of(), Set.of(),
                fixture.key().requirementVersionId(), fixture.key().outlineVersionId(),
                Optional.empty(), Optional.empty(), Optional.of(id(800)), Optional.empty(),
                NOW.plusSeconds(5), new SolutionRiskReport(List.of()));
        sections.insertIfAbsent(sibling);

        assertThatThrownBy(() -> sections.insertIfAbsent(humanVersion(
                fixture, id(813), Optional.empty(), Optional.of(sibling.id()))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sections.insertIfAbsent(humanVersion(
                fixture, id(814), Optional.of(sibling.id()), Optional.empty())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void repeatedInsertWithNanosecondTimestampIsIdempotent() {
        var fixture = seed(900);
        var version = new SectionVersion(id(910), fixture.key().solutionId(), fixture.key().sectionId(),
                SectionVersionKind.HUMAN_EDIT, "人工草稿", List.of(), Set.of(),
                fixture.key().requirementVersionId(), fixture.key().outlineVersionId(),
                Optional.empty(), Optional.empty(), Optional.of(id(900)), Optional.empty(),
                NOW.plusNanos(123_456_789), new SolutionRiskReport(List.of()));

        sections.insertIfAbsent(version);
        assertThat(sections.insertIfAbsent(version)).isEqualTo(version);
    }

    @Test void adoptionRequiresOuterTransactionAndRollsBackWithAggregateFailure() {
        var fixture = seed(1000);
        var adopted = new SectionVersion(id(1010), fixture.key().solutionId(), fixture.key().sectionId(),
                SectionVersionKind.ADOPTED, "人工采纳", List.of(), Set.of(),
                fixture.key().requirementVersionId(), fixture.key().outlineVersionId(),
                Optional.empty(), Optional.empty(), Optional.of(fixture.actorId()), Optional.empty(),
                NOW.plusSeconds(5), new SolutionRiskReport(List.of()));
        assertThatThrownBy(() -> sections.insertIfAbsent(adopted))
                .isInstanceOf(GenerationStateException.class);
        assertThat(sections.findById(adopted.id())).isEmpty();

        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            sections.insertIfAbsent(adopted);
            var solution = new JdbcSolutionRepository(jdbc).findById(fixture.key().solutionId()).orElseThrow();
            var updated = solution.adoptSection(fixture.key().sectionId(), adopted.id(), solution.revision());
            new JdbcSolutionRepository(jdbc).replace(updated, solution.revision() - 1);
        })).isInstanceOf(RuntimeException.class);
        assertThat(sections.findById(adopted.id())).isEmpty();
    }

    private static SectionVersion humanVersion(Fixture fixture, UUID id, Optional<UUID> baseline,
            Optional<UUID> parent) {
        return new SectionVersion(id, fixture.key().solutionId(), fixture.key().sectionId(),
                SectionVersionKind.HUMAN_EDIT, "人工草稿", List.of(), Set.of(),
                fixture.key().requirementVersionId(), fixture.key().outlineVersionId(),
                baseline, parent, Optional.of(fixture.actorId()), Optional.empty(), NOW.plusSeconds(5),
                new SolutionRiskReport(List.of()));
    }

    private static Fixture seed(int base) {
        var actor = id(base);
        var space = id(base + 1);
        var solutionId = id(base + 2);
        var requirementId = id(base + 3);
        var outlineId = id(base + 4);
        var sectionId = id(base + 5);
        var attemptId = id(base + 6);
        var evidenceId = id(base + 7);
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, '测试')")
                .param("id", actor).param("external", actor.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '测试空间', :actor)")
                .param("id", space).param("actor", actor).update();
        var requirement = new RequirementVersion(requirementId, 1,
                List.of(new RequirementItem(id(base + 8), RequirementCategory.UNKNOWN, "待明确", List.of())),
                actor, NOW, "初始录入");
        var outline = new OutlineVersion(outlineId, 1,
                List.of(new OutlineNode(sectionId, "概述", 1, 1, true, "目标", "检索", Set.of()),
                        new OutlineNode(id(base + 11), "相邻章节", 1, 2, false, "目标", "检索", Set.of())), actor, NOW);
        var solution = Solution.create(solutionId, space, actor, SolutionName.of("测试方案"), requirement, NOW);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.executeWithoutResult(status -> {
            var solutions = new JdbcSolutionRepository(jdbc);
            solutions.insert(solution);
            new JdbcRequirementVersionRepository(jdbc).insert(solutionId, requirement);
            var confirmed = solution.confirmRequirements(actor, NOW.plusSeconds(1), 0);
            solutions.replace(confirmed, 0);
            new JdbcOutlineVersionRepository(jdbc).insert(solutionId, outline);
            var revised = confirmed.reviseOutline(outline, 1);
            solutions.replace(revised, 1);
            solutions.replace(revised.confirmOutline(actor, NOW.plusSeconds(2), 2), 2);
        });
        var key = new GenerationKey("section-" + base, solutionId, requirementId,
                outlineId, sectionId, Optional.empty());
        var started = GenerationAttempt.start(attemptId, key, NOW.plusSeconds(3));
        attempts.register(started);
        var retrieving = started.startRetrieval(NOW.plusSeconds(3));
        attempts.replace(retrieving, started.revision());
        var generating = retrieving.recordEvidence(id(base + 9), Set.of(evidenceId), NOW.plusSeconds(3));
        attempts.replace(generating, retrieving.revision());
        var verifying = generating.startVerification(new ModelUsage(10, 20, 0, 100), NOW.plusSeconds(4));
        attempts.replace(verifying, generating.revision());
        return new Fixture(key, verifying, evidenceId, actor);
    }

    private static SectionVersion draft(Fixture fixture, UUID versionId) {
        var claimId = id(999);
        return new SectionVersion(versionId, fixture.key().solutionId(), fixture.key().sectionId(),
                SectionVersionKind.MODEL_DRAFT, "# 概述\n已核验事实。",
                List.of(new Claim(claimId, "已核验事实", true, Set.of(fixture.evidenceId()), Set.of())),
                Set.of(fixture.evidenceId()), fixture.key().requirementVersionId(),
                fixture.key().outlineVersionId(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("approved-model"), NOW.plusSeconds(5),
                new SolutionRiskReport(List.of(new ClaimVerification(claimId, CitationVerdict.SUPPORTED, "SUPPORTED"))));
    }

    private record Fixture(GenerationKey key, GenerationAttempt verifying, UUID evidenceId, UUID actorId) {}

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
