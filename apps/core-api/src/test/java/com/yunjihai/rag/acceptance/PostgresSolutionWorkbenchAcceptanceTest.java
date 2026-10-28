package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.access.*;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.infrastructure.postgres.*;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.solution.SolutionWorkbenchService;
import com.yunjihai.rag.solution.application.*;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.model.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Runs the first workbench slice against migrated PostgreSQL with real ACL and audit adapters. */
class PostgresSolutionWorkbenchAcceptanceTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_solution_acceptance").withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final UUID EDITOR = id(1), REVIEWER = id(2), OUTSIDER = id(3);
    private static final UUID SPACE = id(4), DOCUMENT = id(5), DOCUMENT_VERSION = id(6);
    private static JdbcClient jdbc;
    private static TransactionTemplate tx;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_solution_acceptance SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        seed();
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void createsConfirmsRestoresAndFailsClosedAfterRevocation() {
        var access = access();
        assertThatThrownBy(() -> access.requireView(OUTSIDER, SPACE, NOW))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);

        var commands = commands();
        var original = createInput("知识资产治理方案");
        var created = commands.create(EDITOR, "accept-create", original);
        assertThat(created.phase()).isEqualTo(SolutionPhase.REQUIREMENTS_DRAFT);
        assertThat(commands.create(EDITOR, "accept-create", original)).isEqualTo(created);

        var requirementConfirmed = commands.confirmRequirements(REVIEWER, "accept-confirm-requirements",
                new SolutionCommandService.ConfirmInput(created.solutionId(), created.requirementVersionId(),
                        created.revision()));
        assertThat(requirementConfirmed.phase()).isEqualTo(SolutionPhase.REQUIREMENTS_CONFIRMED);

        var outlineDraft = commands.reviseOutline(EDITOR, "accept-outline",
                new SolutionCommandService.ReviseOutlineInput(created.solutionId(), List.of(
                        new OutlineNode(id(10), "现状与目标", 1, 1, true, "明确治理目标", "检索现状材料",
                                Set.of(RiskType.CUSTOMER_OR_CASE_FACT))), "初次编制大纲",
                        requirementConfirmed.revision()));
        var outlineConfirmed = commands.confirmOutline(REVIEWER, "accept-confirm-outline",
                new SolutionCommandService.ConfirmInput(created.solutionId(), outlineDraft.outlineVersionId().orElseThrow(),
                        outlineDraft.revision()));
        assertThat(outlineConfirmed.phase()).isEqualTo(SolutionPhase.OUTLINE_CONFIRMED);

        // New repository instances read the same state from PostgreSQL after the command objects are discarded.
        var restored = new JdbcSolutionRepository(jdbc).findById(created.solutionId()).orElseThrow();
        assertThat(restored.phase()).isEqualTo(SolutionPhase.OUTLINE_CONFIRMED);
        assertThat(restored.outlineConfirmation()).isPresent();
        assertThat(new JdbcRequirementVersionRepository(jdbc).findById(created.requirementVersionId())).isPresent();
        assertThat(new JdbcOutlineVersionRepository(jdbc).findById(outlineDraft.outlineVersionId().orElseThrow()))
                .isPresent();

        var revised = commands.reviseRequirements(EDITOR, "accept-revise-requirements",
                new SolutionCommandService.ReviseRequirementsInput(created.solutionId(), requirements(id(11)),
                        "客户目标更新", outlineConfirmed.revision()));
        assertThat(revised.phase()).isEqualTo(SolutionPhase.REQUIREMENTS_DRAFT);
        assertThat(new JdbcSolutionRepository(jdbc).findById(created.solutionId()).orElseThrow()
                .outlineConfirmation()).isEmpty();
        assertThatThrownBy(() -> commands.reviseRequirements(EDITOR, "accept-stale",
                new SolutionCommandService.ReviseRequirementsInput(created.solutionId(), requirements(id(12)),
                        "旧修订冲突", outlineConfirmed.revision())))
                .isInstanceOf(ConcurrentSolutionUpdateException.class);

        var confirmedAgain = commands.confirmRequirements(REVIEWER, "accept-confirm-revised-requirements",
                new SolutionCommandService.ConfirmInput(created.solutionId(), revised.requirementVersionId(),
                        revised.revision()));
        var secondOutline = commands.reviseOutline(EDITOR, "accept-second-outline",
                new SolutionCommandService.ReviseOutlineInput(created.solutionId(), List.of(
                        new OutlineNode(id(13), "更新后的目标", 1, 1, true, "覆盖新需求", "检索新材料",
                                Set.of())), "依据新需求重编", confirmedAgain.revision()));
        assertThat(new JdbcOutlineVersionRepository(jdbc)
                .findById(secondOutline.outlineVersionId().orElseThrow()).orElseThrow().number()).isEqualTo(2);
        assertThat(new JdbcOutlineVersionRepository(jdbc)
                .findById(secondOutline.outlineVersionId().orElseThrow()).orElseThrow().changeReason())
                .isEqualTo("依据新需求重编");

        jdbc.sql("UPDATE acl_entry SET revoked_at = :at WHERE space_id = :space AND scope = 'SPACE' AND principal_id = :actor")
                .param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).param("space", SPACE)
                .param("actor", REVIEWER).update();
        assertThatThrownBy(() -> access.requireView(REVIEWER, SPACE, NOW))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
        assertThatThrownBy(() -> commands.confirmRequirements(REVIEWER, "accept-confirm-requirements",
                new SolutionCommandService.ConfirmInput(created.solutionId(), created.requirementVersionId(),
                        created.revision())))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);

        jdbc.sql("UPDATE acl_entry SET revoked_at = :at WHERE document_id = :document AND principal_id = :actor")
                .param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).param("document", DOCUMENT)
                .param("actor", EDITOR).update();
        assertThatThrownBy(() -> commands.create(EDITOR, "accept-create", original))
                .isInstanceOf(SolutionCommandConflictException.class)
                .hasMessage("IDEMPOTENT_RESULT_UNAVAILABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE resource_id = :id")
                .param("id", created.solutionId()).query(Long.class).single()).isEqualTo(7);
        assertThat(jdbc.sql("SELECT count(*) FROM solution_command_idempotency WHERE client_key LIKE 'accept-%'")
                .query(Long.class).single()).isEqualTo(7);
    }

    private static SolutionCommandService commands() {
        var solutions = new JdbcSolutionRepository(jdbc);
        var requirements = new JdbcRequirementVersionRepository(jdbc);
        var outlines = new JdbcOutlineVersionRepository(jdbc);
        var access = access();
        var principals = new EffectivePrincipalSet(new JdbcPrincipalMembershipRepository(jdbc));
        var decisions = new AccessDecisionService(principals, new JdbcAccessDecisionRepository(jdbc));
        var sourceAccess = new SolutionSourceAccess(spaceRead(), new JdbcSolutionDocumentSourcePort(jdbc, decisions));
        SolutionModelPort unavailable = new SolutionModelPort() {
            @Override public <T> SolutionModelResponse<T> execute(SolutionModelRequest request, Class<T> type) {
                throw new UnsupportedOperationException();
            }
        };
        var audit = new AuditService(new JdbcAuditEventStore(jdbc), Clock.fixed(NOW, ZoneOffset.UTC));
        var workbench = new SolutionWorkbenchService(solutions, requirements, outlines, access, unavailable, audit);
        return new SolutionCommandService(workbench, solutions, requirements, outlines, access, sourceAccess,
                new SolutionReviewPolicy(false), new JdbcSolutionCommandIdempotencyStore(jdbc), tx,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SolutionAccessService access() {
        return new SolutionAccessService(spaceRead(), new JdbcRoleAssignmentStore(jdbc));
    }

    private static KnowledgeSpaceReadAuthorization spaceRead() {
        return (user, space, at) -> {
            boolean allowed = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM acl_entry acl JOIN app_user account ON account.id = acl.principal_id
                        WHERE acl.space_id = :space AND acl.scope = 'SPACE' AND acl.principal_type = 'USER'
                          AND acl.principal_id = :actor AND account.enabled AND acl.revoked_at IS NULL
                          AND (acl.expires_at IS NULL OR acl.expires_at > :at))
                    """).param("space", space).param("actor", user)
                    .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).query(Boolean.class).single();
            if (!allowed) throw new AccessDeniedAsNotFoundException();
        };
    }

    private static SolutionCommandService.CreateInput createInput(String name) {
        return new SolutionCommandService.CreateInput(SPACE, name, requirements(id(9)), 0);
    }

    private static List<RequirementItem> requirements(UUID itemId) {
        return List.of(new RequirementItem(itemId, RequirementCategory.GOAL, "建设可审计的知识资产",
                List.of(new RequirementSource(new DocumentVersionRef(DOCUMENT, DOCUMENT_VERSION),
                        new PageLocator(1, null)))));
    }

    private static void seed() {
        for (var user : List.of(EDITOR, REVIEWER, OUTSIDER)) {
            jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, '验收用户')")
                    .param("id", user).param("external", user.toString()).update();
        }
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '虚构验收空间', :actor)")
                .param("id", SPACE).param("actor", EDITOR).update();
        for (var user : List.of(EDITOR, REVIEWER)) {
            jdbc.sql("INSERT INTO acl_entry (id, space_id, scope, principal_type, principal_id) VALUES (:id, :space, 'SPACE', 'USER', :actor)")
                    .param("id", UUID.randomUUID()).param("space", SPACE).param("actor", user).update();
        }
        var roles = new JdbcRoleAssignmentStore(jdbc);
        roles.grantSpaceRole(SPACE, EDITOR, SpaceRole.EDITOR, EDITOR, NOW);
        roles.grantSpaceRole(SPACE, REVIEWER, SpaceRole.REVIEWER_PUBLISHER, EDITOR, NOW);
        jdbc.sql("INSERT INTO document (id, space_id, title) VALUES (:id, :space, '虚构现状材料')")
                .param("id", DOCUMENT).param("space", SPACE).update();
        jdbc.sql("""
                INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by, status)
                VALUES (:id, :document, 1, 'acceptance-only-blob', :actor, 'PUBLISHED')
                """).param("id", DOCUMENT_VERSION).param("document", DOCUMENT).param("actor", EDITOR).update();
        jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :id")
                .param("version", DOCUMENT_VERSION).param("id", DOCUMENT).update();
        for (var user : List.of(EDITOR, REVIEWER)) {
            jdbc.sql("""
                    INSERT INTO acl_entry (id, space_id, document_id, scope, principal_type, principal_id)
                    VALUES (:id, :space, :document, 'DOCUMENT', 'USER', :actor)
                    """).param("id", UUID.randomUUID()).param("space", SPACE)
                    .param("document", DOCUMENT).param("actor", user).update();
        }
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
