package com.yunjihai.rag.runtime;

import com.yunjihai.rag.qa.conflict.EvidenceConflictDetector;
import com.yunjihai.rag.qa.conflict.EvidenceConflictDetectorAutoConfiguration;
import com.yunjihai.rag.qa.conflict.QuestionScopedConflictPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerRuntimeConfigurationTest {
    private static final EvidenceConflictDetector CUSTOM_DETECTOR = ignored -> false;

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues(
                    "spring.profiles.active=local")
            .withConfiguration(AutoConfigurations.of(EvidenceConflictDetectorAutoConfiguration.class));

    @Test
    void registersTheDeterministicDetectorByDefault() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(EvidenceConflictDetector.class);
        });
    }

    @Test
    void defaultConflictDetectorBacksOffWhenDeploymentProvidesACustomImplementation() {
        context.withUserConfiguration(CustomConflictDetectorConfiguration.class).run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).hasSingleBean(EvidenceConflictDetector.class);
            assertThat(application.getBean(EvidenceConflictDetector.class)).isSameAs(CUSTOM_DETECTOR);
        });
    }

    @Test
    void scopedConflictExtensionRequiresAnExplicitSwitchAndExactlyOneImplementation() {
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var policy=new QuestionScopedConflictPolicy() {
            @Override public String version() { return "synthetic-v1"; }
            @Override public Scope classify(com.yunjihai.rag.qa.domain.QaQuestion question,
                    com.yunjihai.rag.qa.conflict.DeterministicEvidenceConflictDetector.ConflictPair pair,
                    java.util.List<com.yunjihai.rag.retrieval.domain.Evidence> candidates) { return Scope.UNRELATED; }
            @Override public AnswerRelevance verifyAnswer(com.yunjihai.rag.qa.domain.QaQuestion question,
                    com.yunjihai.rag.qa.domain.CandidateAnswer answer,com.yunjihai.rag.qa.domain.EvidenceBundle evidence) {
                return AnswerRelevance.RELEVANT;
            }
        };
        var provider=factory.getBeanProvider(QuestionScopedConflictPolicy.class);
        assertThat(WorkerRuntimeConfiguration.selectScopedPolicy(true,provider).enabled()).isFalse();
        factory.addBean("one",policy);
        assertThat(WorkerRuntimeConfiguration.selectScopedPolicy(false,provider).enabled()).isFalse();
        assertThat(WorkerRuntimeConfiguration.selectScopedPolicy(true,provider)).isSameAs(policy);
        factory.addBean("two",policy);
        assertThat(WorkerRuntimeConfiguration.selectScopedPolicy(true,provider).enabled()).isFalse();
    }

    @Test
    void signedReviewManifestLoadsOnlyWhenSignatureMatchesAndUsesSharedHmac(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var digests=new com.yunjihai.rag.infrastructure.crypto.HmacQuestionDigest("k".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(digests.digest("question:备份多久?")).isEqualTo("f104ad12b73a67334bd9f8800fbfb8633897f292f64f59fcd49509fcbef0a6bb");
        assertThat(digests.digest("claim:30天")).isEqualTo("dd0ab1ff24d587790518480468d2b3da09f3aa427a548c7c5f9c1d77e20bf972");
        assertThat(digests.digest("quote:保留30天")).isEqualTo("94f7532903025f96cf6392956c34ffe495ea4051ba1f20095a643f6df44773f4");
        UUIDSet ids=new UUIDSet();
        var policy=com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.class;
        var entry=new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.QuestionReview(digests.digest("question:备份多久?"),
                ids.approval,true,java.util.List.of(new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.DocumentReview(
                ids.document,"a".repeat(64),com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.Relation.RELEVANT)),
                java.util.List.of(new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.ClaimReview(
                        ids.document,ids.version,"node",digests.digest("claim:30天"),digests.digest("quote:保留30天"))));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        String encoded=java.util.Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(
                new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.Manifest(java.util.List.of(entry))));
        var file=directory.resolve("review.json");
        java.nio.file.Files.write(file,mapper.writeValueAsBytes(new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.SignedManifest(
                encoded,digests.digest("manifest:"+encoded))));
        var jdbc=org.springframework.jdbc.core.simple.JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource());
        var config=new WorkerRuntimeConfiguration();
        var loaded=config.reviewedQuestionScopePolicy(jdbc,digests,file.toString());
        assertThat(loaded).isInstanceOf(policy);
        assertThat(loaded.version()).matches("reviewed-v3-[0-9a-f]{32}");
        java.nio.file.Files.write(file,mapper.writeValueAsBytes(new com.yunjihai.rag.qa.conflict.ReviewedQuestionScopePolicy.SignedManifest(encoded,"0".repeat(64))));
        assertThatThrownBy(()->config.reviewedQuestionScopePolicy(jdbc,digests,file.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessage("INVALID_REVIEW_MANIFEST");
    }

    @Test
    void pythonGeneratedManifestApprovesOnlyItsExplicitAliases(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var resource=getClass().getResourceAsStream("/reviewed-alias-manifest.json");
        assertThat(resource).as("Python 生成的合成签名清单").isNotNull();
        var file=directory.resolve("manifest.json");
        try(resource) { java.nio.file.Files.copy(resource,file); }
        var digests=new com.yunjihai.rag.infrastructure.crypto.HmacQuestionDigest("k".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var jdbc=org.springframework.jdbc.core.simple.JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource());
        var policy=new WorkerRuntimeConfiguration().reviewedQuestionScopePolicy(jdbc,digests,file.toString());
        var doc=java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        var version=java.util.UUID.fromString("00000000-0000-0000-0000-000000000002");
        var id=java.util.UUID.randomUUID();
        var evidence=new com.yunjihai.rag.qa.domain.QaEvidence(id,java.util.UUID.randomUUID(),doc,version,"n","制度","保留30天",1,
                new com.yunjihai.rag.retrieval.domain.MarkdownLocator(java.util.List.of("章节"),"n"));
        var claim=new com.yunjihai.rag.qa.domain.GroundedClaim(java.util.UUID.randomUUID(),"30天",
                java.util.List.of(new com.yunjihai.rag.qa.domain.EvidenceSupport(id,"保留30天")));
        var answer=new com.yunjihai.rag.qa.domain.CandidateAnswer(com.yunjihai.rag.qa.domain.CandidateAnswer.Decision.ANSWER,"30天",java.util.List.of(claim));
        var bundle=new com.yunjihai.rag.qa.domain.EvidenceBundle(java.util.List.of(evidence),evidence.excerpt().codePointCount(0,evidence.excerpt().length()));
        for(String text:java.util.List.of("备份多久？","备份保留多长时间？"))
            assertThat(policy.verifyAnswer(com.yunjihai.rag.qa.domain.QaQuestion.normalize(text),answer,bundle))
                    .isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.RELEVANT);
        assertThat(policy.verifyAnswer(com.yunjihai.rag.qa.domain.QaQuestion.normalize("备份一定保留多久？"),answer,bundle))
                .isEqualTo(QuestionScopedConflictPolicy.AnswerRelevance.UNKNOWN);
    }

    private static final class UUIDSet {
        final java.util.UUID approval=java.util.UUID.randomUUID();
        final java.util.UUID document=java.util.UUID.randomUUID();
        final java.util.UUID version=java.util.UUID.randomUUID();
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomConflictDetectorConfiguration {
        @Bean
        EvidenceConflictDetector customConflictDetector() {
            return CUSTOM_DETECTOR;
        }
    }
}
