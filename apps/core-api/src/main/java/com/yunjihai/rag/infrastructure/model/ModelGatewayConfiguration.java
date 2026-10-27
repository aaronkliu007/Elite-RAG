package com.yunjihai.rag.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunjihai.rag.qa.model.ChatCompletionPort;
import com.yunjihai.rag.qa.model.ModelGatewayException;
import com.yunjihai.rag.retrieval.embedding.EmbeddingPort;
import com.yunjihai.rag.retrieval.embedding.EmbeddingUnavailableException;
import com.yunjihai.rag.retrieval.selection.RerankerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovedModelGatewayProperties.class)
public class ModelGatewayConfiguration {

    @Bean(name = "modelGateway")
    ModelGatewayHealthIndicator modelGatewayHealthIndicator(
            ApprovedModelGatewayProperties properties) {
        return new ModelGatewayHealthIndicator(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "rag.ai.enabled", havingValue = "true")
    ModelUsagePolicy modelUsagePolicy(ApprovedModelGatewayProperties properties) {
        return new ModelUsagePolicy(properties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "rag.ai.enabled", havingValue = "true")
    OpenAiCompatibleClient openAiCompatibleClient(
            ApprovedModelGatewayProperties properties, ModelUsagePolicy usagePolicy) {
        return new OpenAiCompatibleClient(properties, new ObjectMapper(), usagePolicy);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.ai.enabled", havingValue = "true")
    EmbeddingPort approvedEmbeddingPort(
            ApprovedModelGatewayProperties properties, OpenAiCompatibleClient client) {
        return new OpenAiEmbeddingAdapter(properties, client);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.ai.enabled", havingValue = "true")
    RerankerPort approvedRerankerPort(
            ApprovedModelGatewayProperties properties, OpenAiCompatibleClient client) {
        return new OpenAiRerankerAdapter(properties, client);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.ai.enabled", havingValue = "true")
    ChatCompletionPort approvedChatCompletionPort(
            ApprovedModelGatewayProperties properties, OpenAiCompatibleClient client) {
        return new OpenAiChatCompletionAdapter(properties, client, new ObjectMapper());
    }

    @Bean
    @ConditionalOnProperty(name = "rag.ai.enabled", havingValue = "false", matchIfMissing = true)
    EmbeddingPort disabledEmbeddingPort() {
        return texts -> { throw new EmbeddingUnavailableException("EMBEDDING_DISABLED"); };
    }

    @Bean
    @ConditionalOnProperty(name = "rag.ai.enabled", havingValue = "false", matchIfMissing = true)
    RerankerPort disabledRerankerPort() {
        return (query, candidates) -> {
            throw new ModelGatewayException(ModelGatewayException.Kind.UNAVAILABLE);
        };
    }

    @Bean
    @ConditionalOnProperty(name = "rag.ai.enabled", havingValue = "false", matchIfMissing = true)
    ChatCompletionPort disabledChatCompletionPort() {
        return prompt -> { throw new ModelGatewayException(ModelGatewayException.Kind.UNAVAILABLE); };
    }
}
