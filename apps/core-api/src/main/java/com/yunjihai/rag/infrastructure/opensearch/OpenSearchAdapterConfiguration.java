package com.yunjihai.rag.infrastructure.opensearch;

import com.yunjihai.rag.audit.AuditSink;
import com.yunjihai.rag.common.UuidV7;
import com.yunjihai.rag.retrieval.EvidenceIdFactory;
import com.yunjihai.rag.retrieval.PrincipalResolverPort;
import com.yunjihai.rag.retrieval.TrustedRetrievalService;
import com.yunjihai.rag.retrieval.domain.RetrievalPolicy;
import com.yunjihai.rag.retrieval.embedding.EmbeddingPort;
import com.yunjihai.rag.retrieval.embedding.EmbeddingUnavailableException;
import com.yunjihai.rag.retrieval.projection.ProjectionCutoverLock;
import com.yunjihai.rag.retrieval.projection.ProjectionDocumentLock;
import com.yunjihai.rag.retrieval.projection.ProjectionEventRepository;
import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import com.yunjihai.rag.retrieval.projection.ProjectionWorkerPolicy;
import com.yunjihai.rag.retrieval.projection.RetrievalProjectionSource;
import com.yunjihai.rag.retrieval.projection.SearchProjectionWriter;
import com.yunjihai.rag.retrieval.search.HybridRecallService;
import com.yunjihai.rag.retrieval.search.LexicalRetriever;
import com.yunjihai.rag.retrieval.search.VectorRetriever;
import com.yunjihai.rag.retrieval.selection.EvidenceSelectionService;
import com.yunjihai.rag.retrieval.selection.RerankerPort;
import com.yunjihai.rag.retrieval.selection.RerankingService;
import com.yunjihai.rag.retrieval.selection.RetrievalAuthorizationPort;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "rag.opensearch.enabled", havingValue = "true")
public class OpenSearchAdapterConfiguration {
    @Bean
    OpenSearchProperties openSearchProperties(
            @Value("${rag.opensearch.endpoint}") URI endpoint,
            @Value("${rag.opensearch.username:}") String username,
            @Value("${rag.opensearch.password:}") String password,
            @Value("${rag.opensearch.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${rag.opensearch.response-timeout:PT10S}") Duration responseTimeout) {
        return new OpenSearchProperties(endpoint,
                username.isBlank() ? null : username, password.isBlank() ? null : password,
                connectTimeout, responseTimeout);
    }

    @Bean(destroyMethod = "close")
    OpenSearchConnection openSearchConnection(OpenSearchProperties properties) {
        return new OpenSearchClientFactory().create(properties);
    }

    @Bean
    OpenSearchClient openSearchClient(OpenSearchConnection connection) {
        return connection.client();
    }

    @Bean
    OpenSearchIndexManager openSearchIndexManager(OpenSearchClient client) {
        return new OpenSearchIndexManager(client);
    }

    @Bean
    SearchProjectionWriter searchProjectionWriter(OpenSearchClient client) {
        return new OpenSearchProjectionWriter(client);
    }

    @Bean
    ProjectionWorkerPolicy projectionWorkerPolicy(
            @Value("${rag.opensearch.worker.batch-size:20}") int batch,
            @Value("${rag.opensearch.worker.space-page-size:100}") int page,
            @Value("${rag.opensearch.worker.lease-duration:PT2M}") Duration lease,
            @Value("${rag.opensearch.worker.max-attempts:5}") int attempts,
            @Value("${rag.opensearch.worker.initial-retry-delay:PT5S}") Duration initial,
            @Value("${rag.opensearch.worker.max-retry-delay:PT5M}") Duration maximum) {
        return new ProjectionWorkerPolicy(batch, page, lease, attempts, initial, maximum);
    }

    @Bean
    ProjectionWorker projectionWorker(
            ProjectionEventRepository events,
            RetrievalProjectionSource source,
            ProjectionDocumentLock databaseLock,
            SearchProjectionWriter writer,
            ProjectionWorkerPolicy policy,
            PlatformTransactionManager transactions,
            Clock clock) {
        var template = new TransactionTemplate(transactions);
        ProjectionDocumentLock transactionalLock = new ProjectionDocumentLock() {
            @Override
            public <T> T withDocumentLock(java.util.UUID documentId, java.util.function.Supplier<T> action) {
                return template.execute(status -> databaseLock.withDocumentLock(documentId, action));
            }
        };
        return new ProjectionWorker(events, source, transactionalLock, writer, policy, clock);
    }

    @Bean
    OpenSearchRebuildService openSearchRebuildService(
            RetrievalProjectionSource source,
            ProjectionEventRepository events,
            SearchProjectionWriter writer,
            OpenSearchIndexManager indexes,
            ProjectionCutoverLock databaseLock,
            PlatformTransactionManager transactions,
            @Value("${rag.opensearch.rebuild.page-size:100}") int pageSize,
            @Value("${rag.opensearch.rebuild.replay-batch-size:500}") int replayBatch,
            @Value("${rag.retrieval.vector.enabled:false}") boolean vectorEnabled,
            @Value("${rag.retrieval.vector.dimension:0}") int vectorDimension) {
        var template = new TransactionTemplate(transactions);
        ProjectionCutoverLock transactionalLock = new ProjectionCutoverLock() {
            @Override public <T> T withShared(java.util.function.Supplier<T> action) {
                return template.execute(status -> databaseLock.withShared(action));
            }
            @Override public <T> T withExclusive(Duration timeout, java.util.function.Supplier<T> action) {
                return template.execute(status -> databaseLock.withExclusive(timeout, action));
            }
        };
        OptionalInt dimension = vectorEnabled ? OptionalInt.of(vectorDimension) : OptionalInt.empty();
        return new OpenSearchRebuildService(
                source, events, writer, indexes, transactionalLock, pageSize, replayBatch, dimension);
    }

    @Bean
    OpenSearchRecallQueryFactory openSearchRecallQueryFactory() { return new OpenSearchRecallQueryFactory(); }

    @Bean
    OpenSearchCandidateMapper openSearchCandidateMapper() { return new OpenSearchCandidateMapper(); }

    @Bean
    EmbeddingPort embeddingPort() {
        return texts -> { throw new EmbeddingUnavailableException("EMBEDDING_DISABLED"); };
    }

    @Bean
    LexicalRetriever lexicalRetriever(
            OpenSearchClient client, OpenSearchRecallQueryFactory queries,
            OpenSearchCandidateMapper mapper) {
        return new OpenSearchLexicalRetriever(client, OpenSearchIndexManager.READ_ALIAS, queries, mapper);
    }

    @Bean
    VectorRetriever vectorRetriever(
            OpenSearchClient client, EmbeddingPort embeddings,
            OpenSearchRecallQueryFactory queries, OpenSearchCandidateMapper mapper,
            @Value("${rag.retrieval.vector.enabled:false}") boolean enabled,
            @Value("${rag.retrieval.vector.dimension:1536}") int dimension) {
        if (!enabled) {
            return (queryText, filter, limit) -> {
                throw new EmbeddingUnavailableException("EMBEDDING_DISABLED");
            };
        }
        return new OpenSearchVectorRetriever(
                client, OpenSearchIndexManager.READ_ALIAS, embeddings, dimension, queries, mapper);
    }

    @Bean(destroyMethod = "close")
    ExecutorService retrievalExecutor() { return Executors.newVirtualThreadPerTaskExecutor(); }

    @Bean
    RetrievalPolicy retrievalPolicy() { return RetrievalPolicy.defaults(); }

    @Bean
    HybridRecallService hybridRecallService(
            LexicalRetriever lexical, VectorRetriever vector,
            ExecutorService executor, RetrievalPolicy policy) {
        return new HybridRecallService(lexical, vector, executor, policy);
    }

    @Bean
    RerankerPort rerankerPort() { return (query, candidates) -> { throw new IllegalStateException("RERANKER_DISABLED"); }; }

    @Bean
    RerankingService rerankingService(
            RerankerPort reranker, ExecutorService executor, RetrievalPolicy policy) {
        return new RerankingService(reranker, executor, policy);
    }

    @Bean
    EvidenceSelectionService evidenceSelectionService(RetrievalPolicy policy) {
        return new EvidenceSelectionService(policy);
    }

    @Bean
    EvidenceIdFactory evidenceIdFactory() { return UuidV7::generate; }

    @Bean
    TrustedRetrievalService trustedRetrievalService(
            PrincipalResolverPort principals, HybridRecallService recall,
            RerankingService reranking, RetrievalAuthorizationPort authorization,
            EvidenceSelectionService selection, EvidenceIdFactory ids,
            RetrievalPolicy policy, Clock clock, AuditSink audit) {
        return new TrustedRetrievalService(
                principals, recall, reranking, authorization, selection, ids, policy, clock, audit);
    }

    @Bean(name = "opensearchRead")
    OpenSearchReadHealthIndicator openSearchReadHealthIndicator(OpenSearchClient client) {
        return new OpenSearchReadHealthIndicator(client);
    }

    @Bean(name = "opensearchProjection")
    OpenSearchProjectionHealthIndicator openSearchProjectionHealthIndicator(OpenSearchIndexManager indexes) {
        return new OpenSearchProjectionHealthIndicator(indexes);
    }

    @Bean(name = "embeddingCapability")
    EmbeddingCapabilityHealthIndicator embeddingCapabilityHealthIndicator() {
        return new EmbeddingCapabilityHealthIndicator(false);
    }
}
