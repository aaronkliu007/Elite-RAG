package com.yunjihai.rag.runtime;

import com.yunjihai.rag.api.idempotency.IdempotencyService;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.infrastructure.minio.MinioBlobReader;
import com.yunjihai.rag.infrastructure.opensearch.OpenSearchIndexManager;
import com.yunjihai.rag.ingestion.BlobStore;
import com.yunjihai.rag.ingestion.DefaultUploadSecurityScanner;
import com.yunjihai.rag.ingestion.DocumentDraftGateway;
import com.yunjihai.rag.ingestion.FileSystemUploadSpooler;
import com.yunjihai.rag.ingestion.HttpParserTransport;
import com.yunjihai.rag.ingestion.IngestionJobService;
import com.yunjihai.rag.ingestion.IngestionWorker;
import com.yunjihai.rag.ingestion.IngestionWorkerPolicy;
import com.yunjihai.rag.ingestion.MinioParserInvocation;
import com.yunjihai.rag.ingestion.ParserClient;
import com.yunjihai.rag.ingestion.ParserContractValidator;
import com.yunjihai.rag.ingestion.UploadPolicy;
import com.yunjihai.rag.ingestion.UploadService;
import com.yunjihai.rag.retrieval.projection.ProjectionWorker;
import com.yunjihai.rag.qa.sanitization.RegexSensitiveContentSanitizer;
import com.yunjihai.rag.qa.sanitization.SensitiveContentSanitizer;
import com.yunjihai.rag.qa.AuthorizedSpaceScopePort;
import com.yunjihai.rag.qa.CitationSupportVerifier;
import com.yunjihai.rag.qa.ExactQuoteCitationSupportVerifier;
import com.yunjihai.rag.qa.GroundedQaService;
import com.yunjihai.rag.qa.ModelCallAuditPort;
import com.yunjihai.rag.qa.MicrometerQaMetrics;
import com.yunjihai.rag.qa.QaMetrics;
import com.yunjihai.rag.qa.QaPersistencePort;
import com.yunjihai.rag.qa.QaPolicy;
import com.yunjihai.rag.qa.QuestionDigestPort;
import com.yunjihai.rag.qa.model.ChatCompletionPort;
import com.yunjihai.rag.qa.evaluation.EvaluationGate;
import com.yunjihai.rag.qa.evaluation.EvaluationRepository;
import com.yunjihai.rag.qa.evaluation.GroundedQaEvaluationService;
import com.yunjihai.rag.qa.evaluation.QaEvaluationUseCase;
import com.yunjihai.rag.retrieval.RetrievalService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableScheduling
public class WorkerRuntimeConfiguration {

    @Bean
    QaMetrics qaMetrics(
            MeterRegistry registry,
            @Value("${rag.qa.metrics.input-cost-per-million-tokens:0}") double inputCost,
            @Value("${rag.qa.metrics.output-cost-per-million-tokens:0}") double outputCost) {
        return new MicrometerQaMetrics(registry, inputCost, outputCost);
    }

    @Bean
    QaPolicy qaPolicy() {
        return QaPolicy.defaults();
    }

    @Bean
    CitationSupportVerifier citationSupportVerifier() {
        return new ExactQuoteCitationSupportVerifier();
    }

    @Bean
    @ConditionalOnBean({
            AuthorizedSpaceScopePort.class,
            RetrievalService.class,
            QaPersistencePort.class,
            ModelCallAuditPort.class,
            QuestionDigestPort.class,
            ChatCompletionPort.class
    })
    GroundedQaService groundedQaService(
            AuthorizedSpaceScopePort authorizedSpaces,
            RetrievalService retrieval,
            QaPersistencePort persistence,
            ModelCallAuditPort audits,
            QuestionDigestPort digests,
            SensitiveContentSanitizer sanitizer,
            ChatCompletionPort chat,
            CitationSupportVerifier citationVerifier,
            QaPolicy policy,
            Clock clock,
            QaMetrics metrics) {
        return new GroundedQaService(
                authorizedSpaces,retrieval,persistence,audits,digests,sanitizer,chat,
                citationVerifier,policy,clock,metrics);
    }

    @Bean
    EvaluationGate evaluationGate() {
        return new EvaluationGate();
    }

    @Bean
    @ConditionalOnBean({GroundedQaService.class,EvaluationRepository.class})
    QaEvaluationUseCase qaEvaluationUseCase(
            GroundedQaService qa,EvaluationRepository repository,EvaluationGate gate,Clock clock) {
        return new GroundedQaEvaluationService(qa,repository,gate,clock);
    }

    @Bean
    SensitiveContentSanitizer sensitiveContentSanitizer(
            @Value("${rag.sanitization.business-terms:}") String configuredTerms) {
        Set<String> terms = Arrays.stream(configuredTerms.split(","))
                .map(String::trim)
                .filter(term -> !term.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new RegexSensitiveContentSanitizer(terms);
    }

    @Bean
    IngestionWorkerPolicy ingestionWorkerPolicy(
            @Value("${rag.ingestion.worker.lease-duration:PT10M}") Duration leaseDuration,
            @Value("${rag.ingestion.worker.parser-timeout-seconds:300}") int parserTimeoutSeconds) {
        return new IngestionWorkerPolicy(leaseDuration, parserTimeoutSeconds);
    }

    @Bean
    UploadPolicy uploadPolicy() {
        return UploadPolicy.defaults();
    }

    @Bean
    FileSystemUploadSpooler uploadSpooler(
            @Value("${rag.upload.quarantine-directory:}") String configuredDirectory) {
        return configuredDirectory.isBlank()
                ? new FileSystemUploadSpooler()
                : new FileSystemUploadSpooler(Path.of(configuredDirectory));
    }

    @Bean
    DefaultUploadSecurityScanner uploadSecurityScanner(UploadPolicy policy) {
        // 本地开发仅启用格式、加密与压缩炸弹检查；正式试点前必须替换企业恶意文件扫描端口。
        return new DefaultUploadSecurityScanner(policy, path -> false);
    }

    @Bean
    HttpClient parserHttpClient(
            @Value("${rag.parser.connect-timeout:PT3S}") Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Bean
    ParserContractValidator parserContractValidator() {
        try (var input = new ClassPathResource(
                "contracts/parser/canonical-document-v1.schema.json").getInputStream()) {
            return ParserContractValidator.from(input);
        } catch (IOException failure) {
            throw new IllegalStateException("无法加载解析响应契约", failure);
        }
    }

    @Bean
    ParserClient parserClient(
            HttpClient parserHttpClient,
            ParserContractValidator validator,
            @Value("${rag.parser.endpoint}") URI endpoint) {
        return new ParserClient(new HttpParserTransport(endpoint, parserHttpClient), validator);
    }

    @Bean
    MinioParserInvocation parserInvocation(
            MinioBlobReader blobs,
            ParserClient parser,
            @Value("${rag.ingestion.worker.temp-directory:}") String configuredDirectory,
            IngestionWorkerPolicy workerPolicy) {
        var directory = configuredDirectory.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "yunect-rag-parser")
                : Path.of(configuredDirectory);
        return new MinioParserInvocation(
                blobs, parser, directory, workerPolicy.parserTimeoutSeconds());
    }

    @Bean
    IngestionWorker ingestionWorker(IngestionJobService jobs, MinioParserInvocation parser) {
        return new IngestionWorker(jobs, parser);
    }

    @Bean(name = "runtimeUploadService")
    UploadService uploadService(
            UploadPolicy policy,
            FileSystemUploadSpooler spooler,
            DefaultUploadSecurityScanner scanner,
            BlobStore blobs,
            DocumentDraftGateway drafts,
            IngestionJobService jobs,
            IdempotencyService idempotency,
            AuditService audit) {
        return new UploadService(policy, spooler, scanner, blobs, drafts, jobs, idempotency, audit);
    }

    @Bean
    @ConditionalOnProperty(name = "rag.workers.enabled", havingValue = "true")
    IngestionWorkerScheduler ingestionWorkerScheduler(
            IngestionWorker worker,
            @Value("${rag.ingestion.worker.id:local-ingestion-worker}") String workerId) {
        return new IngestionWorkerScheduler(worker, workerId);
    }

    @Bean
    @ConditionalOnBean(ProjectionWorker.class)
    @ConditionalOnProperty(name = "rag.workers.enabled", havingValue = "true")
    ProjectionWorkerScheduler projectionWorkerScheduler(
            ProjectionWorker worker,
            @Value("${rag.opensearch.worker.id:local-projection-worker}") String workerId,
            @Value("${rag.opensearch.worker.target-index:" + OpenSearchIndexManager.WRITE_ALIAS + "}")
            String targetIndex) {
        return new ProjectionWorkerScheduler(worker, workerId, targetIndex);
    }
}
