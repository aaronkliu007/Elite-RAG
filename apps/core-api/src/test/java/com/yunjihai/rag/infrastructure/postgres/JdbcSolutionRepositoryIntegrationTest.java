package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.solution.domain.*;
import com.yunjihai.rag.solution.port.*;
import com.yunjihai.rag.knowledge.DocumentVersionRef;
import com.yunjihai.rag.retrieval.domain.*;
import com.yunjihai.rag.access.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class JdbcSolutionRepositoryIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_solution_test").withUsername("migration_owner").withPassword("migration_test_only");
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static JdbcClient jdbc;
    private static TransactionTemplate tx;
    private static JdbcSolutionRepository solutions;
    private static JdbcRequirementVersionRepository requirements;
    private static JdbcOutlineVersionRepository outlines;

    @BeforeAll static void start() throws Exception {
        POSTGRES.start();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_solution_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        solutions = new JdbcSolutionRepository(jdbc);
        requirements = new JdbcRequirementVersionRepository(jdbc);
        outlines = new JdbcOutlineVersionRepository(jdbc);
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void reconstructsAggregateAndImmutableVersionsAcrossConnections() {
        var actor = id(1);
        var space = id(2);
        seed(actor, space);
        var initial = requirement(actor, id(4), 1);
        var solution = Solution.create(id(3), space, actor, SolutionName.of("  测试方案  "), initial, NOW);
        tx.executeWithoutResult(status -> {
            solutions.insert(solution);
            requirements.insert(solution.id(), initial);
        });
        assertThat(new JdbcSolutionRepository(jdbc).findById(solution.id()).orElseThrow().snapshot())
                .isEqualTo(solution.snapshot());
        assertThat(new JdbcRequirementVersionRepository(jdbc).findById(initial.id())).contains(initial);

        var confirmed = solution.confirmRequirements(actor, NOW.plusSeconds(1), 0);
        var outline = new OutlineVersion(id(8), 1, List.of(new OutlineNode(id(9), "概述", 1, 1,
                true, "目标", "检索", Set.of(RiskType.values()[0]))), actor, NOW.plusSeconds(2));
        var revised = confirmed.reviseOutline(outline, 1);
        tx.executeWithoutResult(status -> {
            solutions.replace(confirmed, 0);
            outlines.insert(solution.id(), outline);
            solutions.replace(revised, 1);
        });
        assertThat(new JdbcSolutionRepository(jdbc).findById(solution.id()).orElseThrow().snapshot())
                .isEqualTo(revised.snapshot());
        assertThat(new JdbcOutlineVersionRepository(jdbc).findById(outline.id())).contains(outline);
        assertThatThrownBy(() -> requirements.insert(solution.id(), initial)).isInstanceOf(RuntimeException.class);
    }

    @Test void rejectsStaleReplacementAndCrossSolutionPointers() {
        var actor = id(21);
        var space = id(22);
        seed(actor, space);
        var firstVersion = requirement(actor, id(24), 1);
        var secondVersion = requirement(actor, id(25), 1);
        var first = Solution.create(id(23), space, actor, SolutionName.of("甲"), firstVersion, NOW);
        var second = Solution.create(id(26), space, actor, SolutionName.of("乙"), secondVersion, NOW);
        tx.executeWithoutResult(status -> {
            solutions.insert(first); requirements.insert(first.id(), firstVersion);
            solutions.insert(second); requirements.insert(second.id(), secondVersion);
        });
        var confirmed = first.confirmRequirements(actor, NOW.plusSeconds(1), 0);
        solutions.replace(confirmed, 0);
        assertThatThrownBy(() -> solutions.replace(confirmed, 0))
                .isInstanceOf(ConcurrentSolutionUpdateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                jdbc.sql("UPDATE solution SET current_requirement_version_id = :version WHERE id = :id")
                        .param("version", secondVersion.id()).param("id", first.id()).update()))
                .isInstanceOf(RuntimeException.class);
        assertThat(solutions.findById(first.id()).orElseThrow().currentRequirementVersionId())
                .isEqualTo(firstVersion.id());
    }

    @Test void rollsBackAggregateAndVersionsTogether() {
        var actor = id(31);
        var space = id(32);
        seed(actor, space);
        var version = requirement(actor, id(34), 1);
        var solution = Solution.create(id(33), space, actor, SolutionName.of("回滚"), version, NOW);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            solutions.insert(solution);
            requirements.insert(solution.id(), version);
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(solutions.findById(solution.id())).isEmpty();
        assertThat(requirements.findById(version.id())).isEmpty();
    }

    @Test void storesTypedSourceLocatorAndRejectsForeignSpaceSource() {
        var actor = id(41);
        var space = id(42);
        var foreignSpace = id(43);
        seed(actor, space);
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '其他空间', :actor)")
                .param("id", foreignSpace).param("actor", actor).update();
        var document = id(44);
        var documentVersion = id(45);
        jdbc.sql("INSERT INTO document (id, space_id, title) VALUES (:id, :space, '材料')")
                .param("id", document).param("space", space).update();
        jdbc.sql("""
                INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by)
                VALUES (:id, :document, 1, 'test-blob', :actor)
                """).param("id", documentVersion).param("document", document).param("actor", actor).update();
        var source = new RequirementSource(new DocumentVersionRef(document, documentVersion),
                new PageLocator(2, new NormalizedBoundingBox(0.1, 0.2, 0.8, 0.9)));
        var version = new RequirementVersion(id(47), 1,
                List.of(new RequirementItem(id(48), RequirementCategory.values()[0], "有依据", List.of(source))),
                actor, NOW, "初始录入");
        var solution = Solution.create(id(46), space, actor, SolutionName.of("来源"), version, NOW);
        tx.executeWithoutResult(status -> { solutions.insert(solution); requirements.insert(solution.id(), version); });
        assertThat(requirements.findById(version.id()).orElseThrow()).isEqualTo(version);

        var foreignVersion = new RequirementVersion(id(49), 1,
                List.of(new RequirementItem(id(50), RequirementCategory.values()[0], "跨空间", List.of(source))),
                actor, NOW, "初始录入");
        var foreignSolution = Solution.create(id(51), foreignSpace, actor, SolutionName.of("不合规"), foreignVersion, NOW);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            solutions.insert(foreignSolution); requirements.insert(foreignSolution.id(), foreignVersion);
        })).isInstanceOf(RuntimeException.class);
        assertThat(solutions.findById(foreignSolution.id())).isEmpty();
    }

    @Test void sourceAccessRejectsUnpublishedAndRevokedDocuments() {
        var actor = id(61);
        var space = id(62);
        var document = id(63);
        var version = id(64);
        var acl = id(65);
        seed(actor, space);
        jdbc.sql("INSERT INTO document (id, space_id, title) VALUES (:id, :space, '材料')")
                .param("id", document).param("space", space).update();
        jdbc.sql("""
                INSERT INTO document_version (id, document_id, version_number, source_blob_id, created_by)
                VALUES (:id, :document, 1, 'test-blob', :actor)
                """).param("id", version).param("document", document).param("actor", actor).update();
        jdbc.sql("""
                INSERT INTO acl_entry (id, space_id, scope, principal_type, principal_id)
                VALUES (:id, :space, 'SPACE', 'USER', :actor)
                """).param("id", acl).param("space", space).param("actor", actor).update();
        var principals = new EffectivePrincipalSet(user ->
                new PrincipalMembershipSnapshot(Set.of(), Map.of(), Set.of(), Set.of()));
        var access = new AccessDecisionService(principals, new JdbcAccessDecisionRepository(jdbc));
        var sources = new JdbcSolutionDocumentSourcePort(jdbc, access);
        assertThat(sources.isCurrentPublishedAndReadable(actor, space, document, version, NOW)).isFalse();
        jdbc.sql("UPDATE document_version SET status = 'PUBLISHED' WHERE id = :id")
                .param("id", version).update();
        jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :id")
                .param("version", version).param("id", document).update();
        assertThat(sources.isCurrentPublishedAndReadable(actor, space, document, version, NOW)).isTrue();
        jdbc.sql("UPDATE acl_entry SET revoked_at = :at WHERE id = :id")
                .param("at", OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC))
                .param("id", acl).update();
        assertThat(sources.isCurrentPublishedAndReadable(actor, space, document, version, NOW.plusSeconds(2))).isFalse();
    }

    private static RequirementVersion requirement(UUID actor, UUID versionId, long number) {
        return new RequirementVersion(versionId, number,
                List.of(new RequirementItem(id(5), RequirementCategory.UNKNOWN, "待明确", List.of())),
                actor, NOW, "初始录入");
    }

    private static void seed(UUID actor, UUID space) {
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, '测试')")
                .param("id", actor).param("external", actor.toString()).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, '测试空间', :actor)")
                .param("id", space).param("actor", actor).update();
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(suffix));
    }
}
