package com.yunjihai.rag.infrastructure.postgres;

import com.yunjihai.rag.access.AccessDecisionRepository;
import com.yunjihai.rag.access.AccessDecisionService;
import com.yunjihai.rag.access.AccessDeniedAsNotFoundException;
import com.yunjihai.rag.access.AclCommandRepository;
import com.yunjihai.rag.access.AclService;
import com.yunjihai.rag.access.DocumentSpaceLookup;
import com.yunjihai.rag.access.EffectivePrincipalSet;
import com.yunjihai.rag.access.PrincipalMembershipRepository;
import com.yunjihai.rag.audit.AuditEventStore;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.identity.OrganizationReconciliationPort;
import com.yunjihai.rag.identity.OrganizationSyncService;
import com.yunjihai.rag.identity.SessionRevocationService;
import com.yunjihai.rag.identity.SessionVersionStore;
import com.yunjihai.rag.ingestion.BlobCleanupEligibilityRepository;
import com.yunjihai.rag.ingestion.DocumentDraftGateway;
import com.yunjihai.rag.ingestion.DocumentServiceDraftGateway;
import com.yunjihai.rag.ingestion.IngestionJobRepository;
import com.yunjihai.rag.ingestion.IngestionJobService;
import com.yunjihai.rag.ingestion.OrphanBlobRecorder;
import com.yunjihai.rag.ingestion.ParsedDocumentStore;
import com.yunjihai.rag.knowledge.AuthorizedDocumentQuery;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.knowledge.KnowledgeSpaceRepository;
import com.yunjihai.rag.knowledge.KnowledgeSpaceService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.StringJoiner;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableTransactionManagement(proxyTargetClass = true)
public class PostgresAdapterConfiguration {

    @Bean
    Clock foundationClock() {
        return Clock.systemUTC();
    }

    @Bean
    JdbcAuditEventStore jdbcAuditEventStore(JdbcClient jdbc) {
        return new JdbcAuditEventStore(jdbc);
    }

    @Bean
    JdbcOrganizationReconciliationAdapter jdbcOrganizationReconciliationAdapter(JdbcClient jdbc) {
        return new JdbcOrganizationReconciliationAdapter(jdbc);
    }

    @Bean
    JdbcSessionVersionStore jdbcSessionVersionStore(JdbcClient jdbc) {
        return new JdbcSessionVersionStore(jdbc);
    }

    @Bean
    JdbcKnowledgeSpaceRepository jdbcKnowledgeSpaceRepository(JdbcClient jdbc) {
        return new JdbcKnowledgeSpaceRepository(jdbc);
    }

    @Bean
    JdbcDocumentRepository jdbcDocumentRepository(JdbcClient jdbc) {
        return new JdbcDocumentRepository(jdbc);
    }

    @Bean
    JdbcPrincipalMembershipRepository jdbcPrincipalMembershipRepository(JdbcClient jdbc) {
        return new JdbcPrincipalMembershipRepository(jdbc);
    }

    @Bean
    JdbcAccessDecisionRepository jdbcAccessDecisionRepository(JdbcClient jdbc) {
        return new JdbcAccessDecisionRepository(jdbc);
    }

    @Bean
    JdbcAclCommandRepository jdbcAclCommandRepository(JdbcClient jdbc) {
        return new JdbcAclCommandRepository(jdbc);
    }

    @Bean
    JdbcIngestionJobRepository jdbcIngestionJobRepository(JdbcClient jdbc) {
        return new JdbcIngestionJobRepository(jdbc);
    }

    @Bean
    JdbcParsedDocumentStore jdbcParsedDocumentStore(JdbcClient jdbc) {
        return new JdbcParsedDocumentStore(jdbc);
    }

    @Bean
    JdbcOrphanBlobRecorder jdbcOrphanBlobRecorder(JdbcClient jdbc) {
        return new JdbcOrphanBlobRecorder(jdbc);
    }

    @Bean
    JdbcBlobCleanupEligibilityRepository jdbcBlobCleanupEligibilityRepository(JdbcClient jdbc) {
        return new JdbcBlobCleanupEligibilityRepository(jdbc);
    }

    @Bean
    AuditService auditService(AuditEventStore events, Clock clock) {
        return new AuditService(events, clock);
    }

    @Bean
    SessionRevocationService sessionRevocationService(SessionVersionStore sessions) {
        return new SessionRevocationService(sessions);
    }

    @Bean
    OrganizationSyncService organizationSyncService(
            OrganizationReconciliationPort organizations,
            SessionRevocationService sessions) {
        return new OrganizationSyncService(organizations, sessions);
    }

    @Bean
    EffectivePrincipalSet effectivePrincipalSet(PrincipalMembershipRepository memberships) {
        return new EffectivePrincipalSet(memberships);
    }

    @Bean
    AccessDecisionService accessDecisionService(
            EffectivePrincipalSet principals,
            AccessDecisionRepository decisions) {
        return new AccessDecisionService(principals, decisions);
    }

    @Bean
    KnowledgeWriteAuthorization knowledgeWriteAuthorization() {
        return KnowledgeWriteAuthorization.denyAll();
    }

    @Bean
    DocumentSpaceLookup documentSpaceLookup(JdbcClient jdbc) {
        return (spaceId, documentId) -> jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM document WHERE id = :documentId AND space_id = :spaceId
                        )
                        """)
                .param("spaceId", spaceId)
                .param("documentId", documentId)
                .query(Boolean.class)
                .single();
    }

    @Bean
    AuthorizedDocumentQuery authorizedDocumentQuery(
            JdbcClient jdbc,
            DocumentRepository documents,
            AccessDecisionService access) {
        return (userId, spaceId, at) -> jdbc.sql("""
                        SELECT id
                          FROM document
                         WHERE space_id = :spaceId
                           AND archived = false
                           AND current_published_version_id IS NOT NULL
                         ORDER BY id
                        """)
                .param("spaceId", spaceId)
                .query(java.util.UUID.class)
                .list()
                .stream()
                .filter(documentId -> access.canRead(userId, spaceId, documentId, at))
                .map(documents::findById)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    @Bean
    KnowledgeSpaceReadAuthorization knowledgeSpaceReadAuthorization(
            JdbcClient jdbc,
            EffectivePrincipalSet principals) {
        return (userId, spaceId, at) -> {
            var effectivePrincipals = principals.forUser(userId).stream().toList();
            var matches = new StringJoiner(", ");
            for (int index = 0; index < effectivePrincipals.size(); index++) {
                matches.add("(CAST(:principalType" + index + " AS text), CAST(:principalId" + index + " AS uuid))");
            }
            try {
                var statement = jdbc.sql("""
                                SELECT EXISTS (
                                    SELECT 1
                                    FROM knowledge_space space
                                    JOIN app_user reader ON reader.id = :userId AND reader.enabled
                                    JOIN acl_entry acl ON acl.space_id = space.id
                                    WHERE space.id = :spaceId
                                      AND acl.scope = 'SPACE'
                                      AND acl.revoked_at IS NULL
                                      AND (acl.expires_at IS NULL OR acl.expires_at > :at)
                                      AND (acl.principal_type, acl.principal_id) IN (%s)
                                )
                                """.formatted(matches))
                        .param("userId", userId)
                        .param("spaceId", spaceId)
                        .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
                for (int index = 0; index < effectivePrincipals.size(); index++) {
                    var principal = effectivePrincipals.get(index);
                    statement = statement.param("principalType" + index, principal.type().name())
                            .param("principalId" + index, principal.id());
                }
                if (!statement.query(Boolean.class).single()) {
                    throw new AccessDeniedAsNotFoundException();
                }
            } catch (DataAccessException failure) {
                throw PostgresPersistenceExceptionTranslator.translate(failure);
            }
        };
    }

    @Bean
    KnowledgeSpaceService knowledgeSpaceService(
            KnowledgeSpaceRepository spaces,
            KnowledgeWriteAuthorization authorization) {
        return new KnowledgeSpaceService(spaces, authorization);
    }

    @Bean
    DocumentContentGateway documentContentGateway() {
        return DocumentContentGateway.unavailable();
    }

    @Bean
    DocumentService documentService(
            DocumentRepository documents,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery,
            KnowledgeSpaceReadAuthorization spaceAuthorization,
            KnowledgeWriteAuthorization writeAuthorization,
            AuditService audit) {
        return new DocumentService(documents, access, content, authorizedQuery,
                spaceAuthorization, writeAuthorization, audit);
    }

    @Bean
    AclService aclService(
            AclCommandRepository acl,
            KnowledgeWriteAuthorization authorization,
            DocumentSpaceLookup documents,
            AuditService audit) {
        return new AclService(acl, authorization, documents, audit);
    }

    @Bean
    DocumentDraftGateway documentDraftGateway(DocumentService documents, Clock clock) {
        return new DocumentServiceDraftGateway(documents, clock);
    }

    @Bean
    IngestionJobService ingestionJobService(
            IngestionJobRepository jobs,
            ParsedDocumentStore parsedDocuments,
            DocumentService documents,
            AuditService audit,
            Clock clock) {
        return new IngestionJobService(jobs, parsedDocuments, documents, audit,
                clock, Duration.ofMinutes(2));
    }
}
