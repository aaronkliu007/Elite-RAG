package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.knowledge.DocumentVersionRef;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRetrievalAuthorizationIntegrationTest {
    private static final Instant CHECKED_AT = Instant.parse("2026-09-30T10:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rag_retrieval_authorization_test")
            .withUsername("migration_owner")
            .withPassword("migration_test_only");

    private static CountingDataSource countingDataSource;
    private static JdbcClient jdbc;
    private static JdbcRetrievalAuthorizationAdapter adapter;

    private UUID userId;
    private UUID spaceId;
    private UUID otherSpaceId;
    private UUID parentDepartmentId;
    private UUID childDepartmentId;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE rag_runtime_test LOGIN PASSWORD 'runtime_test_only'");
            statement.execute("ALTER DATABASE rag_retrieval_authorization_test SET rag.runtime_role = 'rag_runtime_test'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var runtime = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "rag_runtime_test", "runtime_test_only");
        countingDataSource = new CountingDataSource(runtime);
        jdbc = JdbcClient.create(countingDataSource);
        adapter = new JdbcRetrievalAuthorizationAdapter(jdbc);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void seedIdentityAndSpaces() {
        jdbc.sql("DELETE FROM retrieval_projection_event").update();
        jdbc.sql("DELETE FROM acl_entry").update();
        jdbc.sql("UPDATE document SET current_published_version_id = NULL").update();
        jdbc.sql("DELETE FROM document_version").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM knowledge_space").update();
        jdbc.sql("DELETE FROM org_membership").update();
        jdbc.sql("DELETE FROM org_unit").update();
        jdbc.sql("DELETE FROM app_user").update();

        userId = UUID.randomUUID();
        spaceId = UUID.randomUUID();
        otherSpaceId = UUID.randomUUID();
        parentDepartmentId = UUID.randomUUID();
        childDepartmentId = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, external_id, display_name) VALUES (:id, :external, 'Reader')")
                .param("id", userId).param("external", userId.toString()).update();
        jdbc.sql("INSERT INTO org_unit (id, external_id, name) VALUES (:id, :external, 'Parent')")
                .param("id", parentDepartmentId).param("external", parentDepartmentId.toString()).update();
        jdbc.sql("INSERT INTO org_unit (id, external_id, parent_id, name) VALUES (:id, :external, :parent, 'Child')")
                .param("id", childDepartmentId).param("external", childDepartmentId.toString())
                .param("parent", parentDepartmentId).update();
        jdbc.sql("INSERT INTO org_membership (user_id, org_unit_id, snapshot_at) VALUES (:user, :department, :at)")
                .param("user", userId).param("department", childDepartmentId)
                .param("at", java.time.OffsetDateTime.parse("2026-09-30T09:00:00Z")).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, 'Primary', :user)")
                .param("id", spaceId).param("user", userId).update();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, 'Other', :user)")
                .param("id", otherSpaceId).param("user", userId).update();
        grant(spaceId, null, "SPACE", "USER", userId, null, null);
        countingDataSource.reset();
    }

    @Test
    void authorizesDirectUserAncestorDepartmentAndUnrestrictedDocumentsInOneSql() {
        var direct = publishedDocument(spaceId, false);
        var department = publishedDocument(spaceId, false);
        var unrestricted = publishedDocument(spaceId, false);
        restrict(direct, "USER", userId, null, null);
        restrict(department, "DEPARTMENT", parentDepartmentId, null, null);
        var requested = Set.of(direct, department, unrestricted);
        countingDataSource.reset();

        var allowed = adapter.authorize(userId, Map.of(spaceId, requested), CHECKED_AT);

        assertEquals(requested, allowed);
        assertEquals(1, countingDataSource.statementCount());
    }

    @Test
    void authorizesMultipleSpacesInOneSqlRoundTrip() {
        grant(otherSpaceId, null, "SPACE", "USER", userId, null, null);
        var primary = publishedDocument(spaceId, false);
        var secondary = publishedDocument(otherSpaceId, false);
        countingDataSource.reset();

        var allowed = adapter.authorize(
                userId,
                Map.of(spaceId, Set.of(primary), otherSpaceId, Set.of(secondary)),
                CHECKED_AT);

        assertEquals(Set.of(primary, secondary), allowed);
        assertEquals(1, countingDataSource.statementCount());
    }

    @Test
    void rejectsExpiredRevokedRestrictedArchivedCrossSpaceAndOldVersions() {
        var expired = publishedDocument(spaceId, false);
        restrict(expired, "USER", userId, CHECKED_AT, null);
        var revoked = publishedDocument(spaceId, false);
        restrict(revoked, "USER", userId, null, CHECKED_AT.minusSeconds(1));
        var restrictedWithoutGrant = publishedDocument(spaceId, false);
        restrict(restrictedWithoutGrant, "USER", UUID.randomUUID(), null, CHECKED_AT.minusSeconds(1));
        var archived = publishedDocument(spaceId, true);
        var crossSpace = publishedDocument(otherSpaceId, false);
        var current = publishedDocument(spaceId, false);
        var oldVersion = addOldPublishedVersion(current.documentId());
        var requested = Set.of(expired, revoked, restrictedWithoutGrant, archived, crossSpace,
                oldVersion, current);
        countingDataSource.reset();

        var allowed = adapter.authorize(userId, Map.of(spaceId, requested), CHECKED_AT);

        assertEquals(Set.of(current), allowed);
        assertEquals(1, countingDataSource.statementCount());
    }

    @Test
    void removedMembershipAndDisabledUserFailClosed() {
        var departmentOnlySpace = UUID.randomUUID();
        jdbc.sql("INSERT INTO knowledge_space (id, name, created_by) VALUES (:id, 'Department', :user)")
                .param("id", departmentOnlySpace).param("user", userId).update();
        grant(departmentOnlySpace, null, "SPACE", "DEPARTMENT", parentDepartmentId, null, null);
        var candidate = publishedDocument(departmentOnlySpace, false);

        assertEquals(Set.of(candidate),
                adapter.authorize(userId, Map.of(departmentOnlySpace, Set.of(candidate)), CHECKED_AT));
        jdbc.sql("DELETE FROM org_membership WHERE user_id = :user").param("user", userId).update();
        assertTrue(adapter.authorize(userId, Map.of(departmentOnlySpace, Set.of(candidate)), CHECKED_AT).isEmpty());
        jdbc.sql("INSERT INTO org_membership (user_id, org_unit_id, snapshot_at) VALUES (:user, :department, :at)")
                .param("user", userId).param("department", childDepartmentId)
                .param("at", java.time.OffsetDateTime.parse("2026-09-30T09:00:00Z")).update();
        jdbc.sql("UPDATE app_user SET enabled = false WHERE id = :user").param("user", userId).update();
        assertTrue(adapter.authorize(userId, Map.of(departmentOnlySpace, Set.of(candidate)), CHECKED_AT).isEmpty());
    }

    @Test
    void emptyAndDuplicateInputsAreBoundedBeforeSql() {
        countingDataSource.reset();
        assertTrue(adapter.authorize(userId, Map.of(spaceId, Set.of()), CHECKED_AT).isEmpty());
        assertEquals(0, countingDataSource.statementCount());

        var candidate = publishedDocument(spaceId, false);
        var duplicates = new LinkedHashSet<DocumentVersionRef>();
        duplicates.add(candidate);
        duplicates.add(candidate);
        countingDataSource.reset();
        assertEquals(Set.of(candidate), adapter.authorize(userId, Map.of(spaceId, duplicates), CHECKED_AT));
        assertEquals(1, countingDataSource.statementCount());

        var tooMany = new LinkedHashSet<DocumentVersionRef>();
        for (int index = 0; index < 31; index++) {
            tooMany.add(new DocumentVersionRef(UUID.randomUUID(), UUID.randomUUID()));
        }
        countingDataSource.reset();
        assertThrows(IllegalArgumentException.class,
                () -> adapter.authorize(userId, Map.of(spaceId, tooMany), CHECKED_AT));
        assertEquals(0, countingDataSource.statementCount());
    }

    @Test
    void databaseFailureReturnsNoPartialAuthorization() {
        var failingDataSource = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("database unavailable");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("database unavailable");
            }
        };
        var failing = new JdbcRetrievalAuthorizationAdapter(JdbcClient.create(failingDataSource));

        assertThrows(PostgresPersistenceExceptionTranslator.Failure.class,
                () -> failing.authorize(userId, Map.of(spaceId,
                        Set.of(new DocumentVersionRef(UUID.randomUUID(), UUID.randomUUID()))), CHECKED_AT));
    }

    private DocumentVersionRef publishedDocument(UUID targetSpace, boolean archived) {
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        jdbc.sql("INSERT INTO document (id, space_id, title, archived) VALUES (:id, :space, 'Doc', :archived)")
                .param("id", document).param("space", targetSpace).param("archived", archived).update();
        jdbc.sql("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_blob_id, status, parse_status, created_by)
                VALUES (:id, :document, 1, 'blob', 'PUBLISHED', 'SUCCEEDED', :user)
                """).param("id", version).param("document", document).param("user", userId).update();
        jdbc.sql("UPDATE document SET current_published_version_id = :version WHERE id = :document")
                .param("version", version).param("document", document).update();
        return new DocumentVersionRef(document, version);
    }

    private DocumentVersionRef addOldPublishedVersion(UUID documentId) {
        UUID version = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO document_version
                    (id, document_id, version_number, source_blob_id, status, parse_status, created_by)
                VALUES (:id, :document, 2, 'old-blob', 'PUBLISHED', 'SUCCEEDED', :user)
                """).param("id", version).param("document", documentId).param("user", userId).update();
        return new DocumentVersionRef(documentId, version);
    }

    private void restrict(
            DocumentVersionRef document, String principalType, UUID principalId,
            Instant expiresAt, Instant revokedAt) {
        grant(spaceId, document.documentId(), "DOCUMENT", principalType, principalId, expiresAt, revokedAt);
    }

    private void grant(
            UUID targetSpace, UUID documentId, String scope, String principalType,
            UUID principalId, Instant expiresAt, Instant revokedAt) {
        var statement = jdbc.sql("""
                INSERT INTO acl_entry
                    (id, space_id, document_id, scope, principal_type, principal_id,
                     expires_at, revoked_at, created_at, updated_at)
                VALUES (:id, :space, :document, :scope, :type, :principal,
                        :expires, :revoked, :at, :at)
                """)
                .param("id", UUID.randomUUID())
                .param("space", targetSpace)
                .param("scope", scope)
                .param("type", principalType)
                .param("principal", principalId)
                .param("at", java.time.OffsetDateTime.parse("2026-09-30T09:00:00Z"));
        statement = documentId == null
                ? statement.param("document", null, java.sql.Types.OTHER)
                : statement.param("document", documentId);
        statement = expiresAt == null
                ? statement.param("expires", null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                : statement.param("expires", java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC));
        statement = revokedAt == null
                ? statement.param("revoked", null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                : statement.param("revoked", java.time.OffsetDateTime.ofInstant(revokedAt, java.time.ZoneOffset.UTC));
        statement.update();
    }

    private static final class CountingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicInteger statements = new AtomicInteger();

        private CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return count(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return count(delegate.getConnection(username, password));
        }

        int statementCount() {
            return statements.get();
        }

        void reset() {
            statements.set(0);
        }

        private Connection count(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement")) statements.incrementAndGet();
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
