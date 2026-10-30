package com.yunjihai.rag.solution.application;

import com.yunjihai.rag.access.role.*;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.audit.*;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.retrieval.domain.Evidence;
import com.yunjihai.rag.solution.proposal.*;
import com.yunjihai.rag.infrastructure.postgres.*;
import com.yunjihai.rag.solution.SolutionWorkbenchService;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.model.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class SolutionCommandServiceIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_command_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static JdbcClient jdbc;
    private static TransactionTemplate tx;
    private static JdbcSolutionRepository solutions;
    private static JdbcRequirementVersionRepository requirements;
    private static JdbcOutlineVersionRepository outlines;
    private static JdbcSolutionCommandIdempotencyStore idempotency;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_command_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        solutions = new JdbcSolutionRepository(jdbc);
        requirements = new JdbcRequirementVersionRepository(jdbc);
        outlines = new JdbcOutlineVersionRepository(jdbc);
        idempotency = new JdbcSolutionCommandIdempotencyStore(jdbc);
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void documentCandidateAdoptionRechecksUncitedSelectedDocumentsAndReplaysAfterRevisionChange() throws Exception {
        var actor = id(2001); var space = id(2002); seed(actor, space);
        var commands = service(AuditSink.noop());
        var created = commands.create(actor, "document-create", create(space, "文档需求方案"));
        var doc = new DocumentVersionRef(id(2003), id(2004));
        jdbc.sql("INSERT INTO document(id,space_id,title) VALUES (:id,:space,'需求文件')")
                .param("id", doc.documentId()).param("space", space).update();
        jdbc.sql("INSERT INTO document_version(id,document_id,version_number,source_blob_id,created_by) "
                + "VALUES (:version,:document,1,'test-object',:actor)")
                .param("version", doc.versionId()).param("document", doc.documentId()).param("actor", actor).update();
        var proposalKey = new ProposalKey(actor, created.solutionId(), ProposalKind.DOCUMENT_REQ, "document-proposal");
        var fingerprint = SolutionRequestDigest.documentProposal(created.solutionId(), created.revision(),
                created.requirementVersionId(), "客户需求", List.of(doc));
        var request = new ProposalRegistrationRequest(proposalKey, created.requirementVersionId(), created.revision(), fingerprint);
        var attempts = new JdbcSolutionProposalAttemptStore(jdbc, tx.getTransactionManager());
        var attempt = attempts.register(request, Duration.ofMinutes(3)).attempt();
        var evidence = new Evidence(space, id(2005), doc.documentId(), doc.versionId(), "node-1", "文件",
                "客户秘密", 0.8, new PageLocator(1, null));
        var snapshots = new JdbcSolutionProposalEvidenceStore(jdbc, tx.getTransactionManager());
        snapshots.save(attempt.id(), ProposalEvidenceSnapshot.from(space, List.of(doc), List.of(evidence)));
        var resultJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "kind", "REQUIREMENTS", "requirementVersionId", created.requirementVersionId(),
                "expectedRevision", created.revision(), "items", List.of(Map.of("itemId", id(2006),
                        "category", "UNKNOWN", "text", "待核对的客户需求", "sources", List.of()))));
        assertThat(attempts.succeed(request, resultJson)).isTrue();
        var allowed = new AtomicBoolean(false);
        SolutionDocumentSourcePort sources = (user, selectedSpace, document, version, at) -> allowed.get();
        var adoption = new DocumentRequirementProposalAdoptionService(solutions,
                com.yunjihai.rag.solution.SolutionAuthorizationPort.allowAll(), sources, attempts, snapshots,
                commands, tx, Clock.fixed(NOW, ZoneOffset.UTC), com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher.noop(),
                new JdbcIdentityAuthorizationLock(jdbc));
        var input = new DocumentRequirementProposalService.Command(actor, created.solutionId(), created.revision(),
                "document-proposal", "客户需求", List.of(doc));
        assertThatThrownBy(() -> adoption.adopt(input, "adopt-key")).isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThat(solutions.findById(created.solutionId()).orElseThrow().revision()).isEqualTo(created.revision());
        allowed.set(true);
        var first = adoption.adopt(input, "adopt-key");
        assertThat(first.revision()).isEqualTo(created.revision() + 1);
        assertThat(adoption.adopt(input, "adopt-key")).isEqualTo(first);
        allowed.set(false);
        assertThatThrownBy(() -> adoption.adopt(input, "adopt-key")).isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test void sameKeyReplaysCommittedReferenceAndDifferentPayloadConflicts() {
        var actor = id(1); var space = id(2);
        seed(actor, space);
        var commands = service(AuditSink.noop());
        var input = create(space, "方案甲");
        var first = commands.create(actor, "client-key-1", input);
        var replay = commands.create(actor, "client-key-1", input);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM solution WHERE space_id = :space")
                .param("space", space).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> commands.create(actor, "client-key-1", create(space, "另一方案")))
                .isInstanceOf(SolutionCommandConflictException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM solution_command_idempotency WHERE actor_id = :actor")
                .param("actor", actor).query(Long.class).single()).isEqualTo(1);
    }

    @Test void auditFailureRollsBackSolutionAndKey() {
        var actor = id(11); var space = id(12);
        seed(actor, space);
        var commands = service(command -> { throw new IllegalStateException("audit unavailable"); });
        assertThatThrownBy(() -> commands.create(actor, "audit-failure", create(space, "回滚方案")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM solution WHERE space_id = :space")
                .param("space", space).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM solution_command_idempotency WHERE actor_id = :actor")
                .param("actor", actor).query(Long.class).single()).isZero();
    }

    @Test void replayAfterSourceOrSpaceRevocationReturnsNoHistoricalBody() {
        var actor = id(21); var space = id(22); var document = id(23); var version = id(24);
        seed(actor, space);
        jdbc.sql("INSERT INTO document (id, space_id, title) VALUES (:id, :space, '材料')")
                .param("id", document).param("space", space).update();
        jdbc.sql("""
                INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by, status)
                VALUES (:id, :document, 1, 'test-blob', :actor, 'PUBLISHED')
                """).param("id", version).param("document", document).param("actor", actor).update();
        jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :id")
                .param("version", version).param("id", document).update();
        var spaceGranted = new AtomicBoolean(true);
        var sourceGranted = new AtomicBoolean(true);
        var commands = service(AuditSink.noop(), (user, target, at) -> {
            if (!spaceGranted.get()) throw new AccessDeniedAsNotFoundException();
        }, (user, target, doc, ver, at) -> sourceGranted.get());
        var input = new SolutionCommandService.CreateInput(space, "带来源方案",
                List.of(new RequirementItem(id(25), RequirementCategory.GOAL, "目标内容",
                        List.of(new RequirementSource(new DocumentVersionRef(document, version),
                                new PageLocator(1, null))))), 0);
        commands.create(actor, "revoked-replay", input);
        sourceGranted.set(false);
        assertThatThrownBy(() -> commands.create(actor, "revoked-replay", input))
                .isInstanceOf(SolutionCommandConflictException.class)
                .hasMessage("IDEMPOTENT_RESULT_UNAVAILABLE");
        sourceGranted.set(true);
        spaceGranted.set(false);
        assertThatThrownBy(() -> commands.create(actor, "revoked-replay", input))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    @Test void selfConfirmationIsRejectedWithoutConsumingKey() {
        var actor = id(31); var space = id(32);
        seed(actor, space);
        var commands = service(AuditSink.noop());
        var created = commands.create(actor, "create-self", create(space, "双人复核"));
        var confirm = new SolutionCommandService.ConfirmInput(created.solutionId(),
                created.requirementVersionId(), created.revision());
        assertThatThrownBy(() -> commands.confirmRequirements(actor, "confirm-self", confirm))
                .isInstanceOf(SolutionLifecycleException.class)
                .hasMessage("SELF_CONFIRMATION_FORBIDDEN");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM solution_command_idempotency
                WHERE actor_id = :actor AND client_key = 'confirm-self'
                """).param("actor", actor).query(Long.class).single()).isZero();
    }

    @Test void concurrentSameKeyCreatesOneSolution() throws Exception {
        var actor = id(41); var space = id(42);
        seed(actor, space);
        var commands = service(AuditSink.noop());
        var input = create(space, "并发方案");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<SolutionCommandResult> task = () -> {
                gate.await();
                return commands.create(actor, "parallel-create", input);
            };
            var one = executor.submit(task);
            var two = executor.submit(task);
            gate.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(two.get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM solution WHERE space_id = :space")
                .param("space", space).query(Long.class).single()).isEqualTo(1);
    }

    @Test void differentKeysWithSameRevisionYieldOneConflictWithoutPersistenceFailure() throws Exception {
        var actor = id(51); var space = id(52);
        seed(actor, space);
        var commands = service(AuditSink.noop());
        var created = commands.create(actor, "different-keys-create", create(space, "并发修订"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Object> first = () -> reviseInRace(commands, actor, created, "different-key-a", id(53), gate);
            Callable<Object> second = () -> reviseInRace(commands, actor, created, "different-key-b", id(54), gate);
            var one = executor.submit(first);
            var two = executor.submit(second);
            gate.countDown();
            var outcomes = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(SolutionCommandResult.class::isInstance).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(ConcurrentSolutionUpdateException.class::isInstance).count())
                    .isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM solution_requirement_version WHERE solution_id = :id")
                .param("id", created.solutionId()).query(Long.class).single()).isEqualTo(2);
    }

    private static Object reviseInRace(SolutionCommandService commands, UUID actor,
            SolutionCommandResult created, String key, UUID itemId, CountDownLatch gate) throws InterruptedException {
        gate.await();
        try {
            return commands.reviseRequirements(actor, key,
                    new SolutionCommandService.ReviseRequirementsInput(created.solutionId(),
                            List.of(new RequirementItem(itemId, RequirementCategory.UNKNOWN, "并发修订内容", List.of())),
                            "并发修订", created.revision()));
        } catch (RuntimeException conflict) {
            return conflict;
        }
    }

    private static SolutionCommandService service(AuditSink audit) {
        return service(audit, (actor, space, at) -> {},
                (actor, space, document, version, at) -> true);
    }

    private static SolutionCommandService service(AuditSink audit,
            com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization spaceRead,
            SolutionDocumentSourcePort documentRead) {
        var access = new SolutionAccessService(spaceRead, new Roles());
        var sources = new SolutionSourceAccess(spaceRead, documentRead);
        SolutionModelPort unavailable = new SolutionModelPort() {
            @Override public <T> SolutionModelResponse<T> execute(SolutionModelRequest request, Class<T> type) {
                throw new UnsupportedOperationException();
            }
        };
        var workbench = new SolutionWorkbenchService(solutions, requirements, outlines, access, unavailable, audit);
        return new SolutionCommandService(workbench, solutions, requirements, outlines, access, sources,
                new SolutionReviewPolicy(false), idempotency, tx, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SolutionCommandService.CreateInput create(UUID space, String name) {
        return new SolutionCommandService.CreateInput(space, name,
                List.of(new RequirementItem(id(100), RequirementCategory.UNKNOWN, "待确认", List.of())), 0);
    }

    private static void seed(UUID actor, UUID space) {
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, '测试')")
                .param("id", actor).param("external", actor.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '测试空间', :actor)")
                .param("id", space).param("actor", actor).update();
    }

    private record Roles() implements RoleAssignmentStore {
        @Override public boolean hasSystemRole(UUID userId, SystemRole role) { return false; }
        @Override public boolean hasAnySystemRole(SystemRole role) { return false; }
        @Override public Set<SpaceRole> activeSpaceRoles(UUID userId, UUID spaceId) { return Set.of(SpaceRole.SPACE_ADMIN); }
        @Override public void grantSystemRole(UUID userId, SystemRole role, UUID actorId, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void grantSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void revokeSpaceRole(UUID spaceId, UUID userId, SpaceRole role, UUID actorId, Instant at) { throw new UnsupportedOperationException(); }
    }

    private static UUID id(int suffix) { return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix)); }
}
