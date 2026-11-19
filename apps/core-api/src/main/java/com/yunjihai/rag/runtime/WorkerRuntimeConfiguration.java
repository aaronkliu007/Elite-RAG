package com.yunjihai.rag.runtime;

import com.yunjihai.rag.api.idempotency.IdempotencyService;
import com.yunjihai.rag.audit.AuditService;
import com.yunjihai.rag.infrastructure.minio.MinioBlobReader;
import com.yunjihai.rag.infrastructure.postgres.JdbcGenerationAttemptStore;
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
import com.yunjihai.rag.qa.QaEvidenceAuthorizationPort;
import com.yunjihai.rag.qa.QaMetrics;
import com.yunjihai.rag.qa.QaPersistencePort;
import com.yunjihai.rag.qa.QaPolicy;
import com.yunjihai.rag.qa.QuestionDigestPort;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector;
import com.yunjihai.rag.qa.conflict.QuestionScopedConflictPolicy;
import org.springframework.beans.factory.ObjectProvider;
import com.yunjihai.rag.qa.model.ChatCompletionPort;
import com.yunjihai.rag.qa.evaluation.EvaluationGate;
import com.yunjihai.rag.qa.evaluation.EvaluationRepository;
import com.yunjihai.rag.qa.evaluation.EvaluationTraceRegistry;
import com.yunjihai.rag.qa.evaluation.GroundedQaEvaluationService;
import com.yunjihai.rag.qa.evaluation.QaEvaluationUseCase;
import com.yunjihai.rag.solution.generation.GenerationPolicy;
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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name="rag.qa.question-scoped-conflicts.builtin-backup-policy",havingValue="true")
    QuestionScopedConflictPolicy backupBudgetQuestionScopedPolicy(org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new com.yunjihai.rag.qa.conflict.BackupBudgetQuestionScopedPolicy(
                new com.yunjihai.rag.infrastructure.postgres.JdbcTableEvidenceSource(jdbc));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="rag.qa.question-scoped-conflicts.reviewed-manifest")
    public QuestionScopedConflictPolicy reviewedQuestionScopePolicy(
            org.springframework.jdbc.core.simple.JdbcClient jdbc, QuestionDigestPort digests,
            @Value("${rag.qa.question-scoped-conflicts.reviewed-manifest}") String manifestPath) {
        try {
            var path=java.nio.file.Path.of(manifestPath);
            if(!path.isAbsolute() || java.nio.file.Files.size(path)>524_288)throw new IllegalArgumentException();
            byte[] bytes=java.nio.file.Files.readAllBytes(path);
            if(bytes.length==0 || bytes.length>524_288)throw new IllegalArgumentException();
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var signed=mapper.readValue(bytes,com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.SignedManifest.class);
            if(signed.payload()==null || signed.signature()==null || !signed.signature().matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            String expected=digests.digest("manifest:"+signed.payload());
            if(!java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    signed.signature().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new IllegalArgumentException();
            byte[] payload=java.util.Base64.getDecoder().decode(signed.payload());
            if(payload.length==0 || payload.length>262_144)throw new IllegalArgumentException();
            var manifest=mapper.readValue(payload,com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.Manifest.class);
            String fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            var source=new com.yunjihai.rag.infrastructure.postgres.JdbcTableEvidenceSource(jdbc);
            return new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy("reviewed-"+fingerprint.substring(0,32),
                    manifest.questions(),source::verifiedContextFingerprint,digests);
        } catch(Exception invalid) {
            throw new IllegalStateException("INVALID_REVIEW_MANIFEST");
        }
    }

    static QuestionScopedConflictPolicy selectScopedPolicy(boolean enabled,ObjectProvider<QuestionScopedConflictPolicy> policies) {
        if(!enabled)return QuestionScopedConflictPolicy.strict();
        var candidates=policies.stream().limit(2).toList();
        return candidates.size()==1 ? candidates.getFirst() : QuestionScopedConflictPolicy.strict();
    }

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
            EvidenceConflictDetector conflictDetector,
            CitationSupportVerifier citationVerifier,
            QaPolicy policy,
            QaEvidenceAuthorizationPort evidenceAuthorization,
            Clock clock,
            QaMetrics metrics,
            EvaluationTraceRegistry evaluationTraces,
            ObjectProvider<QuestionScopedConflictPolicy> scopedPolicies,
            @Value("${rag.qa.question-scoped-conflicts.enabled:false}") boolean scopedConflictsEnabled) {
        return new GroundedQaService(
                authorizedSpaces,retrieval,persistence,audits,digests,sanitizer,chat,
                conflictDetector,citationVerifier,policy,evidenceAuthorization,clock,metrics,evaluationTraces,
                selectScopedPolicy(scopedConflictsEnabled,scopedPolicies));
    }

    @Bean
    EvaluationGate evaluationGate() {
        return new EvaluationGate();
    }

    @Bean
    EvaluationTraceRegistry evaluationTraceRegistry() { return new EvaluationTraceRegistry(); }

    @Bean
    @ConditionalOnBean({GroundedQaService.class,EvaluationRepository.class})
    QaEvaluationUseCase qaEvaluationUseCase(
            GroundedQaService qa,EvaluationRepository repository,EvaluationGate gate,Clock clock,
            EvaluationTraceRegistry traces,com.yunjihai.rag.qa.QuestionDigestPort hasher) {
        return new GroundedQaEvaluationService(qa,repository,gate,clock,traces,hasher);
    }

    @Bean
    @ConditionalOnBean({GroundedQaService.class,EvaluationRepository.class,QuestionDigestPort.class})
    com.yunjihai.rag.qa.evaluation.EvaluationReviewService evaluationReviewService(
            EvaluationRepository repository,GroundedQaService qa,SensitiveContentSanitizer sanitizer,QuestionDigestPort hasher) {
        return new com.yunjihai.rag.qa.evaluation.EvaluationReviewService(
                repository,qa::find,sanitizer,hasher);
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

    @Bean
    @ConditionalOnProperty(name = {"rag.workers.enabled", "rag.solution.generation.recovery.enabled"},
            havingValue = "true")
    GenerationAttemptRecoveryScheduler generationAttemptRecoveryScheduler(
            JdbcGenerationAttemptStore attempts, GenerationPolicy policy,
            @Value("${rag.solution.generation.recovery.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("invalid generation recovery batch size");
        }
        return new GenerationAttemptRecoveryScheduler(
                () -> attempts.expireStale(policy.recoveryAfter(), batchSize));
    }
}
