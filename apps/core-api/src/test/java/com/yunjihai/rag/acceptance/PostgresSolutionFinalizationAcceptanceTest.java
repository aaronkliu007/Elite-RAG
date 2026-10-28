package com.yunjihai.rag.acceptance;

import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.infrastructure.postgres.*;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.retrieval.domain.PageLocator;
import com.yunjihai.rag.solution.SolutionWorkbenchService;
import com.yunjihai.rag.solution.application.*;
import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.finalization.*;
import com.yunjihai.rag.solution.generation.*;
import com.yunjihai.rag.solution.model.*;
import com.yunjihai.rag.solution.verification.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PostgresSolutionFinalizationAcceptanceTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_finalization_test").withUsername("migration_owner")
            .withPassword("migration_test_only");
    private static final Instant NOW = Instant.parse("2026-10-29T02:00:00Z");
    private static final UUID EDITOR = UUID.randomUUID(), REVIEWER = UUID.randomUUID();
    private static final UUID SPACE = UUID.randomUUID(), DOCUMENT = UUID.randomUUID();
    private static final UUID DOCUMENT_VERSION = UUID.randomUUID(), SECTION = UUID.randomUUID();
    private static final UUID CLAIM = UUID.randomUUID(), EVIDENCE = UUID.randomUUID();
    private static JdbcClient jdbc;
    private static DataSourceTransactionManager manager;
    private static TransactionTemplate tx;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_finalization LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_finalization_test SET rag.runtime_role = 'rag_runtime_finalization'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                "rag_runtime_finalization", "runtime_test_only");
        jdbc = JdbcClient.create(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(manager);
        seed();
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void finalizesAtomicallyAndRejectsWordDownloadAfterSourceRevocation() throws Exception {
        var solutions = new JdbcSolutionRepository(jdbc);
        var requirements = new JdbcRequirementVersionRepository(jdbc);
        var outlines = new JdbcOutlineVersionRepository(jdbc);
        var sections = new JdbcSectionVersionRepository(jdbc, manager);
        var confirmations = new JdbcRiskConfirmationRepository(jdbc, manager);
        var audit = new AuditService(new JdbcAuditEventStore(jdbc), Clock.fixed(NOW, ZoneOffset.UTC));
        var access = new SolutionAccessService(spaceRead(), new JdbcRoleAssignmentStore(jdbc));
        var principals = new EffectivePrincipalSet(new JdbcPrincipalMembershipRepository(jdbc));
        var decisions = new AccessDecisionService(principals, new JdbcAccessDecisionRepository(jdbc));
        var documents = new JdbcSolutionDocumentSourcePort(jdbc, decisions);
        var sourceAccess = new SolutionSourceAccess(spaceRead(), documents);
        var authorization = new SolutionGenerationAuthorizationService(access, sourceAccess,
                solutions, requirements);
        var evidenceAccess = new JdbcSolutionEvidenceAccess(jdbc, documents);
        var finalStore = new JdbcSolutionFinalizationStore(jdbc, solutions, audit, manager);
        var finalization = new SolutionFinalizationService(solutions, outlines, sections,
                confirmations, authorization, evidenceAccess, finalStore, audit);
        var export = new SolutionWordExportService(solutions, finalStore, requirements, outlines,
                sections, new JdbcGenerationEvidenceStore(jdbc, manager), access, sourceAccess,
                evidenceAccess, new SolutionWordRenderer(), audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        SolutionModelPort noModel = new SolutionModelPort() {
            @Override public <T> SolutionModelResponse<T> execute(SolutionModelRequest request, Class<T> type) {
                throw new UnsupportedOperationException();
            }
        };
        var workbench = new SolutionWorkbenchService(solutions, requirements, outlines, access, noModel, audit);
        var commands = new SolutionCommandService(workbench, solutions, requirements, outlines,
                access, sourceAccess, new SolutionReviewPolicy(false),
                new JdbcSolutionCommandIdempotencyStore(jdbc), tx, Clock.fixed(NOW, ZoneOffset.UTC));

        var created = commands.create(EDITOR, "create-finalization", new SolutionCommandService.CreateInput(
                SPACE, "验收方案", List.of(new RequirementItem(UUID.randomUUID(), RequirementCategory.GOAL,
                        "建立可信知识库", List.of(new RequirementSource(
                                new DocumentVersionRef(DOCUMENT, DOCUMENT_VERSION), new PageLocator(1, null))))), 0));
        var confirmed = commands.confirmRequirements(REVIEWER, "confirm-finalization-requirement",
                new SolutionCommandService.ConfirmInput(created.solutionId(),
                        created.requirementVersionId(), created.revision()));
        var outlined = commands.reviseOutline(EDITOR, "outline-finalization",
                new SolutionCommandService.ReviseOutlineInput(created.solutionId(), List.of(
                        new OutlineNode(SECTION, "总体设计", 1, 1, true, "说明架构", "检索架构资料", Set.of())),
                        "初次编制", confirmed.revision()));
        var outlineConfirmed = commands.confirmOutline(REVIEWER, "confirm-finalization-outline",
                new SolutionCommandService.ConfirmInput(created.solutionId(),
                        outlined.outlineVersionId().orElseThrow(), outlined.revision()));

        UUID attempt = UUID.randomUUID(), draft = UUID.randomUUID(), adopted = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO solution_generation_attempt
                  (id, client_key, solution_id, requirement_version_id, outline_version_id,
                   section_id, input_fingerprint, status, execution_count, revision, created_at, updated_at)
                VALUES (:id, :key, :solution, :requirement, :outline, :section,
                        :hash, 'SUCCEEDED', 1, 0, :at, :at)
                """).param("id", attempt).param("key", "accept-" + attempt)
                .param("solution", created.solutionId()).param("requirement", created.requirementVersionId())
                .param("outline", outlined.outlineVersionId().orElseThrow()).param("section", SECTION)
                .param("hash", "a".repeat(64)).param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO solution_section_version
                    (id, solution_id, section_id, kind, content, requirement_version_id,
                     outline_version_id, model_id, created_at, attempt_id)
                VALUES (:id, :solution, :section, 'MODEL_DRAFT', '草稿', :requirement,
                        :outline, 'test-model', :at, :attempt)
                """).param("id", draft).param("solution", created.solutionId()).param("section", SECTION)
                .param("requirement", created.requirementVersionId())
                .param("outline", outlined.outlineVersionId().orElseThrow())
                .param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).param("attempt", attempt).update();
        jdbc.sql("""
                INSERT INTO solution_generation_evidence_source
                    (attempt_id, evidence_id, evidence_order, space_id, document_id, version_id,
                     node_id, excerpt_hash)
                VALUES (:attempt, :evidence, 1, :space, :document, :version, 'node-1', :hash)
                """).param("attempt", attempt).param("evidence", EVIDENCE).param("space", SPACE)
                .param("document", DOCUMENT).param("version", DOCUMENT_VERSION)
                .param("hash", "b".repeat(64)).update();
        var claim = new Claim(CLAIM, "性能达到目标", true, Set.of(EVIDENCE),
                Set.of(RiskType.PERFORMANCE_OR_SLA));
        var section = new SectionVersion(adopted, created.solutionId(), SECTION,
                SectionVersionKind.ADOPTED, "正文 <script>alert(1)</script>", List.of(claim),
                Set.of(EVIDENCE), created.requirementVersionId(), outlined.outlineVersionId().orElseThrow(),
                Optional.empty(), Optional.of(draft), Optional.of(EDITOR), Optional.empty(), NOW,
                new SolutionRiskReport(List.of(new ClaimVerification(CLAIM,
                        CitationVerdict.HUMAN_CONFIRMATION_REQUIRED, "RISK_REVIEW_REQUIRED"))));
        tx.executeWithoutResult(status -> {
            sections.insertIfAbsent(section);
            var current = solutions.findById(created.solutionId()).orElseThrow();
            solutions.replace(current.adoptSection(SECTION, adopted, current.revision()), current.revision());
        });
        var evidenceStore = new JdbcGenerationEvidenceStore(jdbc, manager);
        UUID regeneratedAttempt = UUID.randomUUID(), regeneratedDraft = UUID.randomUUID();
        UUID newerEvidence = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO solution_generation_attempt
                  (id, client_key, solution_id, requirement_version_id, outline_version_id,
                   section_id, baseline_section_version_id, input_fingerprint, status,
                   execution_count, revision, created_at, updated_at)
                VALUES (:id, :key, :solution, :requirement, :outline, :section,
                        :baseline, :hash, 'SUCCEEDED', 1, 0, :at, :at)
                """).param("id", regeneratedAttempt).param("key", "regenerate-" + regeneratedAttempt)
                .param("solution", created.solutionId()).param("requirement", created.requirementVersionId())
                .param("outline", outlined.outlineVersionId().orElseThrow()).param("section", SECTION)
                .param("baseline", adopted).param("hash", "c".repeat(64))
                .param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO solution_section_version
                    (id, solution_id, section_id, kind, content, requirement_version_id,
                     outline_version_id, baseline_section_version_id, parent_version_id,
                     model_id, created_at, attempt_id)
                VALUES (:id, :solution, :section, 'MODEL_DRAFT', '重新生成的草稿', :requirement,
                        :outline, :baseline, :baseline, 'test-model', :at, :attempt)
                """).param("id", regeneratedDraft).param("solution", created.solutionId())
                .param("section", SECTION).param("requirement", created.requirementVersionId())
                .param("outline", outlined.outlineVersionId().orElseThrow())
                .param("baseline", adopted).param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .param("attempt", regeneratedAttempt).update();
        jdbc.sql("""
                INSERT INTO solution_generation_evidence_source
                    (attempt_id, evidence_id, evidence_order, space_id, document_id, version_id,
                     node_id, excerpt_hash)
                VALUES (:attempt, :evidence, 1, :space, :document, :version, 'node-2', :hash)
                """).param("attempt", regeneratedAttempt).param("evidence", newerEvidence)
                .param("space", SPACE).param("document", DOCUMENT).param("version", DOCUMENT_VERSION)
                .param("hash", "d".repeat(64)).update();
        assertThat(evidenceStore.findBySectionVersionId(regeneratedDraft))
                .extracting(GenerationEvidenceSource::evidenceId).containsExactly(newerEvidence);
        var versionQuery = new JdbcSectionEditVersionQuery(jdbc, sections);
        var editing = new SolutionSectionEditingService(solutions, sections, versionQuery,
                evidenceStore, new CurrentGenerationEvidenceAuthorization(documents),
                authorization, tx, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        var beforeEdit = solutions.findById(created.solutionId()).orElseThrow();
        var failingEditing = new SolutionSectionEditingService(solutions, sections, versionQuery,
                evidenceStore, new CurrentGenerationEvidenceAuthorization(documents),
                authorization, tx, command -> { throw new IllegalStateException("audit unavailable"); },
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> failingEditing.save(EDITOR, created.solutionId(), SECTION, adopted, null,
                beforeEdit.revision(), "审计失败不能留下候选", "audit-failure"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM solution_section_version WHERE kind = 'HUMAN_EDIT'")
                .query(Long.class).single()).isZero();
        var edited = editing.save(EDITOR, created.solutionId(), SECTION, adopted, null,
                beforeEdit.revision(), "人工修订后的正文 <script>alert(1)</script>", "human-edit-1");
        assertThat(edited.verification().verifications()).singleElement().satisfies(check ->
                assertThat(check.verdict()).isEqualTo(CitationVerdict.HUMAN_CONFIRMATION_REQUIRED));
        assertThat(evidenceStore.findBySectionVersionId(edited.id())).extracting(GenerationEvidenceSource::evidenceId)
                .containsExactly(EVIDENCE);
        assertThat(versionQuery.latestEdit(created.solutionId(), SECTION, adopted)).contains(edited);
        assertThatThrownBy(() -> editing.save(EDITOR, created.solutionId(), SECTION, adopted,
                null, beforeEdit.revision(), "并发覆盖", "human-edit-2"))
                .isInstanceOf(ConcurrentSolutionUpdateException.class);
        var finalEdit = editing.save(EDITOR, created.solutionId(), SECTION, adopted, edited.id(),
                beforeEdit.revision(), "人工二次修订后的正文 <script>alert(1)</script>", "human-edit-3");
        assertThat(versionQuery.latestEdit(created.solutionId(), SECTION, adopted)).contains(finalEdit);
        assertThat(editing.save(EDITOR, created.solutionId(), SECTION, adopted, null,
                beforeEdit.revision() - 1, edited.content(), "human-edit-1")).isEqualTo(edited);
        assertThat(evidenceStore.findBySectionVersionId(finalEdit.id()))
                .extracting(GenerationEvidenceSource::evidenceId).containsExactly(EVIDENCE);
        UUID editedAdoptedId = UUID.randomUUID();
        var editedAdopted = new SectionVersion(editedAdoptedId, created.solutionId(), SECTION,
                SectionVersionKind.ADOPTED, finalEdit.content(), finalEdit.claims(), finalEdit.evidenceIds(),
                finalEdit.requirementVersionId(), finalEdit.outlineVersionId(), Optional.of(adopted),
                Optional.of(finalEdit.id()), Optional.of(EDITOR), Optional.empty(), NOW,
                finalEdit.verification());
        tx.executeWithoutResult(status -> {
            sections.insertIfAbsent(editedAdopted);
            var latest = solutions.findById(created.solutionId()).orElseThrow();
            solutions.replace(latest.adoptSection(SECTION, editedAdoptedId, latest.revision()), latest.revision());
        });
        assertThat(evidenceStore.findBySectionVersionId(editedAdoptedId))
                .extracting(GenerationEvidenceSource::evidenceId).containsExactly(EVIDENCE);
        var current = solutions.findById(created.solutionId()).orElseThrow();
        assertThatThrownBy(() -> finalization.finalizeSolution(REVIEWER,
                created.solutionId(), current.revision(), NOW))
                .isInstanceOf(SolutionLifecycleException.class)
                .hasMessageContaining("RISK_CONFIRMATION_REQUIRED");
        var failingRiskService = new SolutionFinalizationService(solutions, outlines, sections,
                confirmations, authorization, evidenceAccess, finalStore,
                command -> { throw new IllegalStateException("audit unavailable"); });
        var failingRiskCommand = new SolutionRiskConfirmationCommandService(failingRiskService, tx, audit);
        assertThatThrownBy(() -> failingRiskCommand.confirm(REVIEWER, created.solutionId(),
                editedAdoptedId, finalEdit.claims().getFirst().claimId(), "已核验测试报告", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(confirmations.findBySolutionId(created.solutionId())).isEmpty();
        var riskCommand = new SolutionRiskConfirmationCommandService(finalization, tx, audit);
        var signature = riskCommand.confirm(REVIEWER, created.solutionId(), editedAdoptedId,
                finalEdit.claims().getFirst().claimId(),
                "已核验测试报告", NOW);
        assertThat(confirmations.findBySolutionId(created.solutionId())).contains(signature);
        var failingStore = new JdbcSolutionFinalizationStore(jdbc, solutions,
                command -> { throw new IllegalStateException("audit unavailable"); }, manager);
        var failingFinalization = new SolutionFinalizationService(solutions, outlines, sections,
                confirmations, authorization, evidenceAccess, failingStore, audit);
        assertThatThrownBy(() -> failingFinalization.finalizeSolution(REVIEWER,
                created.solutionId(), current.revision(), NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM solution_finalized_version").query(Long.class).single())
                .isZero();
        assertThat(solutions.findById(created.solutionId()).orElseThrow().finalizedVersionId()).isEmpty();
        var finalized = finalization.finalizeSolution(REVIEWER,
                created.solutionId(), current.revision(), NOW);
        assertThat(finalStore.findById(finalized.id())).contains(finalized);
        assertThat(solutions.findById(created.solutionId()).orElseThrow().finalizedVersionId())
                .contains(finalized.id());
        assertThatThrownBy(() -> editing.save(EDITOR, created.solutionId(), SECTION, editedAdoptedId,
                null, solutions.findById(created.solutionId()).orElseThrow().revision(),
                "定稿后不得原位修订", "edit-after-final"))
                .isInstanceOf(SolutionLifecycleException.class).hasMessageContaining("SOLUTION_FINALIZED");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'SOLUTION_FINALIZE' AND result = 'SUCCEEDED'")
                .query(Long.class).single()).isEqualTo(1);
        try (var docx = new XWPFDocument(new ByteArrayInputStream(
                export.export(REVIEWER, created.solutionId(), finalized.id())))) {
            assertThat(docx.getParagraphs().stream().map(p -> p.getText()).toList())
                    .contains("人工二次修订后的正文 <script>alert(1)</script>");
            assertThat(docx.getParagraphs().stream().map(p -> p.getText()).toList())
                    .anyMatch(line -> line.contains("节点 node-1"));
            assertThat(docx.getParagraphs().stream().map(p -> p.getText()).toList())
                    .anyMatch(line -> line.contains("页码 1"));
        }
        jdbc.sql("UPDATE acl_entry SET revoked_at = :at WHERE document_id = :document AND principal_id = :reviewer")
                .param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .param("document", DOCUMENT).param("reviewer", REVIEWER).update();
        assertThatThrownBy(() -> export.export(REVIEWER, created.solutionId(), finalized.id()))
                .isInstanceOf(AccessDeniedAsNotFoundException.class);
    }

    private static KnowledgeSpaceReadAuthorization spaceRead() {
        return (actor, space, at) -> {
            boolean allowed = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM acl_entry
                      WHERE space_id = :space AND scope = 'SPACE' AND principal_type = 'USER'
                        AND principal_id = :actor AND revoked_at IS NULL)
                    """).param("space", space).param("actor", actor).query(Boolean.class).single();
            if (!allowed) throw new AccessDeniedAsNotFoundException();
        };
    }

    private static void seed() {
        for (var actor : List.of(EDITOR, REVIEWER)) {
            jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, '验收用户')")
                    .param("id", actor).param("external", actor.toString()).update();
        }
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '验收空间', :actor)")
                .param("id", SPACE).param("actor", EDITOR).update();
        for (var actor : List.of(EDITOR, REVIEWER)) {
            jdbc.sql("INSERT INTO acl_entry (id, space_id, scope, principal_type, principal_id) VALUES (:id, :space, 'SPACE', 'USER', :actor)")
                    .param("id", UUID.randomUUID()).param("space", SPACE).param("actor", actor).update();
        }
        var roles = new JdbcRoleAssignmentStore(jdbc);
        roles.grantSpaceRole(SPACE, EDITOR, SpaceRole.EDITOR, EDITOR, NOW);
        roles.grantSpaceRole(SPACE, REVIEWER, SpaceRole.REVIEWER_PUBLISHER, EDITOR, NOW);
        jdbc.sql("INSERT INTO document (id, space_id, title) VALUES (:id, :space, '引用资料')")
                .param("id", DOCUMENT).param("space", SPACE).update();
        jdbc.sql("""
                INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by, status)
                VALUES (:id, :document, 1, 'test-blob', :actor, 'PUBLISHED')
                """).param("id", DOCUMENT_VERSION).param("document", DOCUMENT).param("actor", EDITOR).update();
        jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :document")
                .param("version", DOCUMENT_VERSION).param("document", DOCUMENT).update();
        for (var actor : List.of(EDITOR, REVIEWER)) {
            jdbc.sql("""
                    INSERT INTO acl_entry (id, space_id, document_id, scope, principal_type, principal_id)
                    VALUES (:id, :space, :document, 'DOCUMENT', 'USER', :actor)
                    """).param("id", UUID.randomUUID()).param("space", SPACE)
                    .param("document", DOCUMENT).param("actor", actor).update();
        }
    }
}
