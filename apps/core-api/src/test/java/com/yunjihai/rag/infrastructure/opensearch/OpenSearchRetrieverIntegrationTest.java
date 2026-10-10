package com.yunjihai.rag.infrastructure.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.access.PrincipalRef;
import com.yunjihai.rag.access.PrincipalType;
import com.yunjihai.rag.retrieval.domain.MarkdownLocator;
import com.yunjihai.rag.retrieval.domain.RecallSource;
import com.yunjihai.rag.retrieval.domain.RetrievalFilter;
import com.yunjihai.rag.retrieval.embedding.EmbeddingUnavailableException;
import com.yunjihai.rag.retrieval.embedding.EmbeddingVector;
import com.yunjihai.rag.retrieval.projection.ProjectionAclGrant;
import com.yunjihai.rag.retrieval.projection.ProjectionNode;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSearchRetrieverIntegrationTest {
    private static final String INDEX = "chunks-retriever";
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-09-30T10:00:00Z");
    private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID OTHER_SPACE = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEPARTMENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ROLE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_USER = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID DEPLOY_DOCUMENT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RECOVERY_DOCUMENT = UUID.fromString("10000000-0000-0000-0000-000000000002");

    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:3.8.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static OpenSearchConnection connection;
    private static OpenSearchLexicalRetriever lexical;
    private static OpenSearchProjectionWriter writer;
    private static URI endpoint;

    @BeforeAll
    static void startOpenSearch() throws Exception {
        OPENSEARCH.start();
        endpoint = URI.create("http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
        connection = new OpenSearchClientFactory().create(new OpenSearchProperties(endpoint, null, null));
        var indexes = new OpenSearchIndexManager(connection.client());
        indexes.createGeneration(INDEX, OptionalInt.of(4));
        writer = new OpenSearchProjectionWriter(connection.client());

        writer.apply(INDEX, snapshot(SPACE, DEPLOY_DOCUMENT, "部署方案", "Kubernetes 部署方案与灰度发布",
                Set.of(grant("USER", USER, null)), Set.of(), false, "软件", 1));
        writer.apply(INDEX, snapshot(SPACE, RECOVERY_DOCUMENT, "容灾方案", "数据库双活与异地容灾架构",
                Set.of(grant("DEPARTMENT", DEPARTMENT, null)),
                Set.of(grant("ROLE", ROLE, null)), true, "软件", 2));
        writer.apply(INDEX, snapshot(SPACE, UUID.randomUUID(), "过期文档", "过期机密正文",
                Set.of(grant("USER", USER, EFFECTIVE_AT)), Set.of(), false, "软件", 3));
        writer.apply(INDEX, snapshot(SPACE, UUID.randomUUID(), "拒绝文档", "绝密项目正文",
                Set.of(grant("USER", OTHER_USER, null)), Set.of(), false, "软件", 4));
        writer.apply(INDEX, snapshot(OTHER_SPACE, UUID.randomUUID(), "其他空间", "部署方案不应跨空间",
                Set.of(grant("USER", USER, null)), Set.of(), false, "软件", 5));

        addEmbedding(versionId(DEPLOY_DOCUMENT) + ":node-1", List.of(1.0, 0.0, 0.0, 0.0));
        addEmbedding(versionId(RECOVERY_DOCUMENT) + ":node-1", List.of(0.0, 1.0, 0.0, 0.0));
        indexes.refresh(INDEX);
        var queryFactory = new OpenSearchRecallQueryFactory();
        lexical = new OpenSearchLexicalRetriever(
                connection.client(), INDEX, queryFactory, new OpenSearchCandidateMapper());
    }

    @AfterAll
    static void stopOpenSearch() throws Exception {
        if (connection != null) connection.close();
        OPENSEARCH.stop();
    }

    @Test
    void lexicalRecallAppliesSpaceAclExpiryAndMetadataFilters() {
        var allowed = lexical.retrieve("部署方案", filter(Set.of(user()), Map.of("industry", "软件")), 10);
        var denied = lexical.retrieve("绝密项目", filter(Set.of(user()), Map.of()), 10);
        var expired = lexical.retrieve("过期机密", filter(Set.of(user()), Map.of()), 10);

        assertEquals(List.of(DEPLOY_DOCUMENT), allowed.stream().map(item -> item.documentId()).toList());
        assertEquals(RecallSource.LEXICAL, allowed.getFirst().source());
        assertEquals(1, allowed.getFirst().sourceRank());
        assertInstanceOf(MarkdownLocator.class, allowed.getFirst().locator());
        assertTrue(denied.isEmpty());
        assertTrue(expired.isEmpty());
    }

    @Test
    void documentRestrictionRequiresBothSpaceAndDocumentAuthorization() {
        var missingDocumentGrant = lexical.retrieve(
                "异地容灾", filter(Set.of(department()), Map.of()), 10);
        var bothGrants = lexical.retrieve(
                "异地容灾", filter(Set.of(department(), role()), Map.of()), 10);

        assertTrue(missingDocumentGrant.isEmpty());
        assertEquals(List.of(RECOVERY_DOCUMENT),
                bothGrants.stream().map(item -> item.documentId()).toList());
    }

    @Test
    void recallRejectsNodeThatIsNotMarkedAsCurrentPublishedProjection() throws Exception {
        UUID staleDocument = UUID.randomUUID();
        writer.apply(INDEX, snapshot(SPACE, staleDocument, "历史投影", "历史投影唯一词",
                Set.of(grant("USER", USER, null)), Set.of(), false, "软件", 40));
        String id = versionId(staleDocument) + ":node-1";
        var body = new ObjectMapper().writeValueAsString(
                Map.of("doc", Map.of("currentPublished", false)));
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint.resolve("/" + INDEX + "/_update/"
                                + id.replace(":", "%3A")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint.resolve("/" + INDEX + "/_refresh"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());

        assertTrue(lexical.retrieve("历史投影唯一词", filter(Set.of(user()), Map.of()), 10).isEmpty());
    }

    @Test
    void rejectsUnknownMetadataAndLimitsAboveFifty() {
        assertThrows(IllegalArgumentException.class,
                () -> lexical.retrieve("部署", filter(Set.of(user()), Map.of("unknown", "x")), 10));
        assertThrows(IllegalArgumentException.class,
                () -> lexical.retrieve("部署", filter(Set.of(user()), Map.of()), 51));
    }

    @Test
    void vectorRecallUsesDeterministicEmbeddingAndSameSecurityFilter() {
        var vector = new OpenSearchVectorRetriever(
                connection.client(), INDEX,
                text -> new EmbeddingVector(List.of(1.0, 0.0, 0.0, 0.0)),
                4, new OpenSearchRecallQueryFactory(), new OpenSearchCandidateMapper());

        var candidates = vector.retrieve("部署", filter(Set.of(user()), Map.of()), 10);

        assertEquals(List.of(DEPLOY_DOCUMENT), candidates.stream().map(item -> item.documentId()).toList());
        assertEquals(RecallSource.VECTOR, candidates.getFirst().source());
    }

    @Test
    void vectorBranchFailsExplicitlyForUnavailableInvalidOrFakeVectors() {
        var unavailable = new OpenSearchVectorRetriever(
                connection.client(), INDEX,
                text -> { throw new EmbeddingUnavailableException("EMBEDDING_DISABLED"); },
                4, new OpenSearchRecallQueryFactory(), new OpenSearchCandidateMapper());
        var wrongDimension = new OpenSearchVectorRetriever(
                connection.client(), INDEX,
                text -> new EmbeddingVector(List.of(1.0, 0.0)),
                4, new OpenSearchRecallQueryFactory(), new OpenSearchCandidateMapper());

        assertThrows(EmbeddingUnavailableException.class,
                () -> unavailable.retrieve("部署", filter(Set.of(user()), Map.of()), 10));
        assertThrows(EmbeddingUnavailableException.class,
                () -> wrongDimension.retrieve("部署", filter(Set.of(user()), Map.of()), 10));
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingVector(List.of(Double.NaN, 0.0)));

        var called = new AtomicBoolean();
        var guarded = new OpenSearchVectorRetriever(
                connection.client(), INDEX,
                text -> {
                    called.set(true);
                    return new EmbeddingVector(List.of(1.0, 0.0, 0.0, 0.0));
                },
                4, new OpenSearchRecallQueryFactory(), new OpenSearchCandidateMapper());
        assertThrows(IllegalArgumentException.class,
                () -> guarded.retrieve("  ", filter(Set.of(user()), Map.of()), 10));
        assertTrue(!called.get());
    }

    private static RetrievalFilter filter(Set<PrincipalRef> principals, Map<String, String> metadata) {
        return new RetrievalFilter(Set.of(SPACE), principals, true, EFFECTIVE_AT, metadata);
    }

    private static PrincipalRef user() { return new PrincipalRef(PrincipalType.USER, USER); }
    private static PrincipalRef department() { return new PrincipalRef(PrincipalType.DEPARTMENT, DEPARTMENT); }
    private static PrincipalRef role() { return new PrincipalRef(PrincipalType.ROLE, ROLE); }

    private static ProjectionAclGrant grant(String type, UUID id, Instant expiresAt) {
        return new ProjectionAclGrant(type + ":" + id, expiresAt);
    }

    private static RetrievalProjectionSnapshot snapshot(
            UUID space, UUID document, String title, String text,
            Set<ProjectionAclGrant> spaceAcl, Set<ProjectionAclGrant> documentAcl,
            boolean restricted, String industry, long sequence) {
        UUID version = versionId(document);
        String indexId = version + ":node-1";
        var node = new ProjectionNode(
                indexId, UUID.nameUUIDFromBytes(indexId.getBytes(StandardCharsets.UTF_8)),
                "node-1", "PARAGRAPH", text, List.of("章节"), 0,
                new MarkdownLocator(List.of("章节"), "node-1"), 0.9);
        return new RetrievalProjectionSnapshot(
                space, document, version, title, 1, sequence, EFFECTIVE_AT.minusSeconds(60),
                EFFECTIVE_AT.minusSeconds(120), spaceAcl, documentAcl, restricted,
                Map.of("industry", industry, "year", "2026"), List.of(node), true);
    }

    private static UUID versionId(UUID document) {
        return UUID.nameUUIDFromBytes((document + ":version").getBytes(StandardCharsets.UTF_8));
    }

    private static void addEmbedding(String id, List<Double> vector) throws Exception {
        String body = new ObjectMapper().writeValueAsString(Map.of("doc", Map.of("embedding", vector)));
        var request = HttpRequest.newBuilder(endpoint.resolve("/" + INDEX + "/_update/" + id.replace(":", "%3A")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }
}
