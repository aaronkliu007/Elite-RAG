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
import com.yunjihai.rag.access.role.RbacKnowledgeAuthorization;
import com.yunjihai.rag.access.role.RoleAssignmentService;
import com.yunjihai.rag.access.role.RoleAssignmentStore;
import com.yunjihai.rag.access.role.SpaceRole;
import com.yunjihai.rag.api.idempotency.IdempotencyService;
import com.yunjihai.rag.api.idempotency.IdempotencyStore;
import com.yunjihai.rag.identity.local.BootstrapAdministrator;
import com.yunjihai.rag.identity.local.BootstrapAdminProperties;
import com.yunjihai.rag.identity.OrganizationReconciliationPort;
import com.yunjihai.rag.identity.OrganizationSyncService;
import com.yunjihai.rag.identity.SessionRevocationService;
import com.yunjihai.rag.identity.SessionVersionStore;
import com.yunjihai.rag.identity.local.BCryptPasswordHasher;
import com.yunjihai.rag.identity.local.LocalAuthenticationService;
import com.yunjihai.rag.identity.local.LocalCredentialStore;
import com.yunjihai.rag.identity.local.LoginSourceRateLimiter;
import com.yunjihai.rag.identity.local.PasswordHasher;
import com.yunjihai.rag.identity.local.UserAccountStore;
import com.yunjihai.rag.identity.local.UserAdministrationService;
import com.yunjihai.rag.ingestion.BlobCleanupEligibilityRepository;
import com.yunjihai.rag.ingestion.DocumentDraftGateway;
import com.yunjihai.rag.ingestion.DocumentServiceDraftGateway;
import com.yunjihai.rag.ingestion.IngestionJobRepository;
import com.yunjihai.rag.ingestion.IngestionJobService;
import com.yunjihai.rag.ingestion.IngestionWorkerPolicy;
import com.yunjihai.rag.ingestion.OrphanBlobRecorder;
import com.yunjihai.rag.ingestion.ParsedDocumentStore;
import com.yunjihai.rag.infrastructure.minio.MinioBlobReader;
import com.yunjihai.rag.infrastructure.crypto.HmacQuestionDigest;
import com.yunjihai.rag.knowledge.AuthorizedDocumentQuery;
import com.yunjihai.rag.knowledge.DocumentContentGateway;
import com.yunjihai.rag.knowledge.DocumentCreationAuthorization;
import com.yunjihai.rag.knowledge.DocumentParseErrorQuery;
import com.yunjihai.rag.knowledge.DocumentRepository;
import com.yunjihai.rag.knowledge.DocumentReviewStore;
import com.yunjihai.rag.knowledge.DocumentService;
import com.yunjihai.rag.knowledge.DocumentStatus;
import com.yunjihai.rag.knowledge.DocumentVersionStatusQuery;
import com.yunjihai.rag.knowledge.KnowledgeSpaceReadAuthorization;
import com.yunjihai.rag.knowledge.KnowledgeSpaceRepository;
import com.yunjihai.rag.knowledge.KnowledgeSpaceService;
import com.yunjihai.rag.knowledge.KnowledgeWriteAuthorization;
import com.yunjihai.rag.retrieval.projection.ProjectionChangePublisher;
import com.yunjihai.rag.retrieval.projection.ProjectionCutoverLock;
import com.yunjihai.rag.retrieval.projection.ProjectionDocumentLock;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSource;
import com.yunjihai.rag.retrieval.PrincipalResolverPort;
import com.yunjihai.rag.retrieval.selection.RetrievalAuthorizationPort;
import com.yunjihai.rag.qa.AuthorizedSpaceScopePort;
import com.yunjihai.rag.qa.ModelCallAuditPort;
import com.yunjihai.rag.qa.QaEvidenceAuthorizationPort;
import com.yunjihai.rag.qa.QaActorRateLimiter;
import com.yunjihai.rag.qa.QaPersistencePort;
import com.yunjihai.rag.qa.QaSourceAccessPort;
import com.yunjihai.rag.qa.QuestionDigestPort;
import com.yunjihai.rag.solution.application.SolutionAccessService;
import com.yunjihai.rag.solution.application.SolutionSourceAccess;
import com.yunjihai.rag.solution.application.SolutionReviewPolicy;
import com.yunjihai.rag.qa.evaluation.EvaluationDataset;
import com.yunjihai.rag.qa.evaluation.EvaluationRepository;
import com.yunjihai.rag.qa.evaluation.EvaluationTraceRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.StringJoiner;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableTransactionManagement(proxyTargetClass = true)
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class PostgresAdapterConfiguration {

    @Bean
    SolutionAccessService solutionAccessService(KnowledgeSpaceReadAuthorization spaces, RoleAssignmentStore roles) {
        return new SolutionAccessService(spaces, roles);
    }

    @Bean
    JdbcSolutionDocumentSourcePort jdbcSolutionDocumentSourcePort(JdbcClient jdbc, AccessDecisionService access) {
        return new JdbcSolutionDocumentSourcePort(jdbc, access);
    }

    @Bean
    SolutionSourceAccess solutionSourceAccess(KnowledgeSpaceReadAuthorization spaces,
            JdbcSolutionDocumentSourcePort documents) {
        return new SolutionSourceAccess(spaces, documents);
    }

    @Bean
    SolutionReviewPolicy solutionReviewPolicy(
            @Value("${rag.solution.review.allow-self-confirmation:false}") boolean allowSelfConfirmation) {
        return new SolutionReviewPolicy(allowSelfConfirmation);
    }

    @Bean
    JdbcSolutionRepository jdbcSolutionRepository(JdbcClient jdbc) {
        return new JdbcSolutionRepository(jdbc);
    }

    @Bean
    JdbcRequirementVersionRepository jdbcRequirementVersionRepository(JdbcClient jdbc) {
        return new JdbcRequirementVersionRepository(jdbc);
    }

    @Bean
    JdbcOutlineVersionRepository jdbcOutlineVersionRepository(JdbcClient jdbc) {
        return new JdbcOutlineVersionRepository(jdbc);
    }

    @Bean
    Clock foundationClock() {
        return Clock.systemUTC();
    }

    @Bean
    JdbcAuditEventStore jdbcAuditEventStore(JdbcClient jdbc) {
        return new JdbcAuditEventStore(jdbc);
    }

    @Bean
    AuthorizedSpaceScopePort authorizedSpaceScopePort(JdbcClient jdbc) {
        return new JdbcAuthorizedSpaceScope(jdbc);
    }

    @Bean
    QaPersistencePort qaPersistencePort(JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new JdbcQaPersistenceAdapter(
                jdbc, new TransactionTemplate(transactions), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Bean
    ModelCallAuditPort modelCallAuditPort(JdbcClient jdbc) {
        return new JdbcModelCallAuditAdapter(jdbc);
    }

    @Bean
    QaActorRateLimiter qaActorRateLimiter(
            JdbcClient jdbc,
            @Value("${rag.qa.actor-rate-limit.window:PT1M}") Duration window,
            @Value("${rag.qa.actor-rate-limit.max-requests:20}") int maxRequests) {
        return new JdbcQaActorRateLimiter(jdbc,window,maxRequests);
    }

    @Bean
    QaSourceAccessPort qaSourceAccessPort(JdbcClient jdbc) {
        return new JdbcQaSourceAccessAdapter(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Bean
    QaEvidenceAuthorizationPort qaEvidenceAuthorizationPort(JdbcClient jdbc) {
        return new JdbcQaEvidenceAuthorizationAdapter(jdbc);
    }

    @Bean
    EvaluationDataset evaluationDataset() {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        try(var input=PostgresAdapterConfiguration.class.getResourceAsStream(
                "/contracts/qa/golden-dataset-v1.json")) {
            if(input==null)throw new IllegalStateException("grounded QA golden dataset is missing");
            return json.readValue(input,EvaluationDataset.class);
        } catch(IOException failure) {
            throw new IllegalStateException("grounded QA golden dataset is invalid",failure);
        }
    }

    @Bean
    EvaluationRepository evaluationRepository(
            JdbcClient jdbc, EvaluationDataset dataset, EvaluationTraceRegistry traces) {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        return new JdbcEvaluationRepository(jdbc, json, Map.of(dataset.id(), dataset), traces);
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${rag.qa.hmac-key:}')")
    QuestionDigestPort questionDigestPort(@Value("${rag.qa.hmac-key}") String key) {
        return new HmacQuestionDigest(key.getBytes(StandardCharsets.UTF_8));
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
    JdbcSystemAdministratorDisableProtection jdbcSystemAdministratorDisableProtection(JdbcClient jdbc) {
        return new JdbcSystemAdministratorDisableProtection(jdbc);
    }

    @Bean
    JdbcUserAccountStore jdbcUserAccountStore(JdbcClient jdbc) {
        return new JdbcUserAccountStore(jdbc);
    }

    @Bean
    JdbcLocalCredentialStore jdbcLocalCredentialStore(JdbcClient jdbc) {
        return new JdbcLocalCredentialStore(jdbc);
    }

    @Bean
    JdbcRoleAssignmentStore jdbcRoleAssignmentStore(JdbcClient jdbc) {
        return new JdbcRoleAssignmentStore(jdbc);
    }

    @Bean
    JdbcIdempotencyStore jdbcIdempotencyStore(JdbcClient jdbc) {
        return new JdbcIdempotencyStore(jdbc);
    }

    @Bean
    JdbcLoginSourceRateLimiter jdbcLoginSourceRateLimiter(
            JdbcClient jdbc,
            @Value("${rag.identity.login-source-hmac-key}") String hmacKey) {
        return new JdbcLoginSourceRateLimiter(jdbc, hmacKey.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    PasswordHasher localPasswordHasher() {
        return new BCryptPasswordHasher();
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
    JdbcDocumentReviewStore jdbcDocumentReviewStore(JdbcClient jdbc) {
        return new JdbcDocumentReviewStore(jdbc);
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
    ProjectionEventRepository projectionEventRepository(JdbcClient jdbc) {
        return new JdbcProjectionEventRepository(jdbc);
    }

    @Bean
    RetrievalProjectionSource retrievalProjectionSource(JdbcClient jdbc) {
        return new JdbcRetrievalProjectionSource(jdbc);
    }

    @Bean
    PrincipalResolverPort principalResolverPort(JdbcClient jdbc, EffectivePrincipalSet principals) {
        return (userId, at) -> {
            boolean enabled = jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :id AND enabled)")
                    .param("id", userId)
                    .query(Boolean.class)
                    .single();
            return enabled ? principals.forUser(userId) : java.util.Set.of();
        };
    }

    @Bean
    ProjectionDocumentLock projectionDocumentLock(JdbcClient jdbc) {
        return new JdbcProjectionDocumentLock(jdbc);
    }

    @Bean
    ProjectionCutoverLock projectionCutoverLock(JdbcClient jdbc) {
        return new JdbcProjectionCutoverLock(jdbc);
    }

    @Bean
    ProjectionChangePublisher projectionChangePublisher(
            JdbcClient jdbc,
            ProjectionEventRepository events,
            ProjectionDocumentLock documents,
            ProjectionCutoverLock cutover) {
        return new JdbcProjectionChangePublisher(jdbc, events, documents, cutover);
    }

    @Bean
    RetrievalAuthorizationPort retrievalAuthorizationPort(JdbcClient jdbc) {
        return new JdbcRetrievalAuthorizationAdapter(jdbc);
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
    LocalAuthenticationService localAuthenticationService(
            UserAccountStore accounts,
            LocalCredentialStore credentials,
            LoginSourceRateLimiter sourceRateLimiter,
            PasswordHasher passwordHasher,
            Clock clock) {
        char[] dummyPassword = "dummy-password-never-used".toCharArray();
        try {
            return new LocalAuthenticationService(
                    accounts, credentials, sourceRateLimiter, passwordHasher, clock,
                    passwordHasher.hash(dummyPassword));
        } finally {
            java.util.Arrays.fill(dummyPassword, '\0');
        }
    }

    @Bean
    UserAdministrationService userAdministrationService(
            UserAccountStore accounts,
            LocalCredentialStore credentials,
            PasswordHasher passwordHasher,
            SessionRevocationService sessions,
            AuditService audit,
            Clock clock,
            JdbcSystemAdministratorDisableProtection administratorProtection) {
        return new UserAdministrationService(
                accounts, credentials, passwordHasher, sessions, audit, clock, administratorProtection);
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
    KnowledgeWriteAuthorization knowledgeWriteAuthorization(RoleAssignmentStore roles) {
        return new RbacKnowledgeAuthorization(roles);
    }

    @Bean
    DocumentCreationAuthorization documentCreationAuthorization(
            KnowledgeSpaceRepository spaces,
            KnowledgeWriteAuthorization roles) {
        return DocumentCreationAuthorization.backedBy(spaces, roles);
    }

    @Bean
    RoleAssignmentService roleAssignmentService(
            RoleAssignmentStore roles,
            AclCommandRepository acl,
            AuditService audit,
            ProjectionChangePublisher projections,
            KnowledgeWriteAuthorization authorization) {
        return new RoleAssignmentService(roles, acl, audit, projections, authorization);
    }

    @Bean
    IdempotencyService idempotencyService(
            IdempotencyStore store,
            ObjectMapper json,
            Clock clock,
            @Value("${rag.idempotency.retention:24h}") Duration retention) {
        return new IdempotencyService(store, json, clock, retention);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "rag.bootstrap-admin",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    BootstrapAdministrator bootstrapAdministrator(
            UserAdministrationService users,
            RoleAssignmentService roles,
            BootstrapAdminProperties properties,
            Clock clock) {
        return new BootstrapAdministrator(users, roles, properties, clock);
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
        return new AuthorizedDocumentQuery() {
            @Override
            public java.util.List<com.yunjihai.rag.knowledge.DocumentAggregate> listAuthorized(
                    java.util.UUID userId, java.util.UUID spaceId, java.time.Instant at) {
                return listAuthorized(userId, spaceId, at, 0, 100);
            }

            @Override
            public java.util.List<com.yunjihai.rag.knowledge.DocumentAggregate> listAuthorized(
                    java.util.UUID userId,
                    java.util.UUID spaceId,
                    java.time.Instant at,
                    int offset,
                    int limit) {
                if (offset < 0 || limit < 1 || limit > 100) {
                    throw new IllegalArgumentException("分页参数不正确");
                }
                var ids = jdbc.sql("""
                                WITH RECURSIVE enabled_reader(user_id) AS (
                                    SELECT id FROM app_user WHERE id = :userId AND enabled
                                ),
                                department_tree(department_id, parent_id) AS (
                                    SELECT department.id, department.parent_id
                                      FROM enabled_reader reader
                                      JOIN org_membership membership ON membership.user_id = reader.user_id
                                      JOIN org_unit department
                                        ON department.id = membership.org_unit_id AND department.enabled
                                    UNION
                                    SELECT parent.id, parent.parent_id
                                      FROM department_tree child
                                      JOIN org_unit parent ON parent.id = child.parent_id AND parent.enabled
                                ),
                                effective_principal(principal_type, principal_id) AS (
                                    SELECT 'USER'::text, user_id FROM enabled_reader
                                    UNION
                                    SELECT 'DEPARTMENT'::text, department_id FROM department_tree
                                )
                                SELECT document.id
                                  FROM document
                                  JOIN document_version version
                                    ON version.id = document.current_published_version_id
                                   AND version.document_id = document.id
                                   AND version.status = 'PUBLISHED'
                                 WHERE document.space_id = :spaceId
                                   AND NOT document.archived
                                   AND EXISTS (
                                       SELECT 1
                                         FROM acl_entry acl
                                         JOIN effective_principal principal
                                           ON principal.principal_type = acl.principal_type
                                          AND principal.principal_id = acl.principal_id
                                        WHERE acl.space_id = :spaceId
                                          AND acl.scope = 'SPACE'
                                          AND acl.revoked_at IS NULL
                                          AND (acl.expires_at IS NULL OR acl.expires_at > :checkedAt)
                                   )
                                   AND (
                                       NOT EXISTS (
                                           SELECT 1 FROM acl_entry history
                                            WHERE history.space_id = :spaceId
                                              AND history.document_id = document.id
                                              AND history.scope = 'DOCUMENT'
                                       )
                                       OR EXISTS (
                                           SELECT 1
                                             FROM acl_entry acl
                                             JOIN effective_principal principal
                                               ON principal.principal_type = acl.principal_type
                                              AND principal.principal_id = acl.principal_id
                                            WHERE acl.space_id = :spaceId
                                              AND acl.document_id = document.id
                                              AND acl.scope = 'DOCUMENT'
                                              AND acl.revoked_at IS NULL
                                              AND (acl.expires_at IS NULL OR acl.expires_at > :checkedAt)
                                       )
                                   )
                                 ORDER BY document.id
                                 LIMIT :limit OFFSET :offset
                                """)
                        .param("userId", userId)
                        .param("spaceId", spaceId)
                        .param("checkedAt", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                        .param("limit", limit)
                        .param("offset", offset)
                        .query(java.util.UUID.class)
                        .list();
                return ids.stream()
                        .map(documents::findById)
                        .flatMap(java.util.Optional::stream)
                        .toList();
            }
        };
    }

    @Bean
    DocumentVersionStatusQuery documentVersionStatusQuery(JdbcClient jdbc) {
        return version -> jdbc.sql("""
                        SELECT status
                          FROM document_version
                         WHERE id = :versionId
                           AND document_id = :documentId
                        """)
                .param("versionId", version.versionId())
                .param("documentId", version.documentId())
                .query(String.class)
                .optional()
                .map(DocumentStatus::valueOf)
                .orElseThrow(AccessDeniedAsNotFoundException::new);
    }

    @Bean
    DocumentParseErrorQuery documentParseErrorQuery(JdbcClient jdbc) {
        return versionId -> jdbc.sql("""
                        SELECT error_code
                          FROM ingestion_job
                         WHERE version_id = :versionId
                           AND error_code IS NOT NULL
                         ORDER BY updated_at DESC, id DESC
                         LIMIT 1
                        """)
                .param("versionId", versionId)
                .query(String.class)
                .optional();
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
            KnowledgeWriteAuthorization authorization,
            RoleAssignmentService roles) {
        return new KnowledgeSpaceService(
                spaces,
                authorization,
                (spaceId, creatorId, at) -> roles.grantSpaceRole(
                        creatorId, spaceId, creatorId, SpaceRole.SPACE_ADMIN, at));
    }

    @Bean
    DocumentContentGateway documentContentGateway(JdbcClient jdbc, MinioBlobReader blobs) {
        return new JdbcDocumentContentGateway(jdbc, blobs);
    }

    @Bean
    DocumentService documentService(
            DocumentRepository documents,
            AccessDecisionService access,
            DocumentContentGateway content,
            AuthorizedDocumentQuery authorizedQuery,
            KnowledgeSpaceReadAuthorization spaceAuthorization,
            KnowledgeWriteAuthorization writeAuthorization,
            AuditService audit,
            ProjectionChangePublisher projections,
            DocumentReviewStore reviews,
            DocumentCreationAuthorization creationAuthorization) {
        return new DocumentService(documents, access, content, authorizedQuery,
                spaceAuthorization, writeAuthorization, audit, projections, reviews,
                creationAuthorization);
    }

    @Bean
    AclService aclService(
            AclCommandRepository acl,
            KnowledgeWriteAuthorization authorization,
            DocumentSpaceLookup documents,
            AuditService audit,
            ProjectionChangePublisher projections) {
        return new AclService(acl, authorization, documents, audit, projections);
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
            Clock clock,
            IngestionWorkerPolicy workerPolicy) {
        return new IngestionJobService(jobs, parsedDocuments, documents, audit,
                clock, workerPolicy.leaseDuration());
    }
}
