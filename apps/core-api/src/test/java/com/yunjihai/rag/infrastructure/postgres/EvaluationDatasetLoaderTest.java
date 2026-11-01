package com.yunjihai.rag.infrastructure.postgres;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class EvaluationDatasetLoaderTest {
    @TempDir Path temporary;

    @Test void externalDatasetCannotBeCombinedWithSyntheticFixture() {
        var config=new PostgresAdapterConfiguration();
        assertThatThrownBy(()->config.evaluationDataset(temporary.resolve("data.json").toString(),true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FIXTURE");
        assertThat(config.evaluationDataset("",true).active()).isTrue();
    }

    @Test void usesBundledDatasetByDefault() {
        var dataset=EvaluationDatasetLoader.load("");
        assertThat(dataset.active()).isTrue();
        assertThat(dataset.cases()).hasSizeGreaterThanOrEqualTo(50);
    }

    @Test void readsExplicitAbsoluteDatasetFile() throws Exception {
        var original=EvaluationDatasetLoader.load("");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        Path file=temporary.resolve("审核集.json");
        var edited=new com.yunjihai.rag.qa.evaluation.EvaluationDataset(original.id(),"reviewed-v2",
                original.promptVersion(),original.sanitizationVersion(),original.cases(),true);
        mapper.writeValue(file.toFile(),edited);
        assertThat(EvaluationDatasetLoader.load(file.toString())).isEqualTo(edited);
    }

    @Test void springFactoryUsesConfiguredFile() throws Exception {
        var original=EvaluationDatasetLoader.load("");
        var edited=new com.yunjihai.rag.qa.evaluation.EvaluationDataset(original.id(),"spring-v2",
                original.promptVersion(),original.sanitizationVersion(),original.cases(),true);
        Path file=temporary.resolve("spring.json");
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValue(file.toFile(),edited);
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "test",java.util.Map.of("RAG_QA_EVALUATION_DATASET_PATH",file.toString())));
            context.getBeanFactory().registerSingleton("configuration",new PostgresAdapterConfiguration());
            var definition=org.springframework.beans.factory.support.BeanDefinitionBuilder
                    .genericBeanDefinition(com.yunjihai.rag.qa.evaluation.EvaluationDataset.class)
                    .setFactoryMethodOnBean("evaluationDataset","configuration").setAutowireMode(org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR).getBeanDefinition();
            context.registerBeanDefinition("dataset",definition);
            context.refresh();
            assertThat(context.getBean(com.yunjihai.rag.qa.evaluation.EvaluationDataset.class)).isEqualTo(edited);
        }
    }

    @Test void rejectsInvalidMissingRelativeAndOversizedFilesWithoutFallback() throws Exception {
        Path invalid=temporary.resolve("invalid.json");
        Files.writeString(invalid,"{broken");
        Path nullJson=temporary.resolve("null.json");
        Files.writeString(nullJson,"null");
        assertThatThrownBy(()->EvaluationDatasetLoader.load(nullJson.toString())).isInstanceOf(IllegalStateException.class);
        Path oversized=temporary.resolve("oversized.json");
        Files.write(oversized,new byte[10 * 1024 * 1024 + 1]);
        assertThatThrownBy(()->EvaluationDatasetLoader.load(invalid.toString()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->EvaluationDatasetLoader.load(temporary.resolve("missing.json").toString()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->EvaluationDatasetLoader.load("relative.json"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->EvaluationDatasetLoader.load(oversized.toString()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsDuplicateFieldsAndExtraJsonDocuments() throws Exception {
        var file=temporary.resolve("duplicate.json");
        Files.writeString(file,"{\"id\":\"x\",\"id\":\"y\"}");
        assertThatThrownBy(()->EvaluationDatasetLoader.load(file.toString())).isInstanceOf(IllegalStateException.class);
        var bundled=EvaluationDatasetLoader.class.getResourceAsStream("/contracts/qa/golden-dataset-v1.json");
        assertThat(bundled).isNotNull();
        try(bundled) { Files.writeString(file,new String(bundled.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)+" {} "); }
        assertThatThrownBy(()->EvaluationDatasetLoader.load(file.toString())).isInstanceOf(IllegalStateException.class);
    }
}
