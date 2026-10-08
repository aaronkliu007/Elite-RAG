package com.yunjihai.rag.infrastructure.minio;

import com.yunjihai.rag.ingestion.BlobStore;
import com.yunjihai.rag.ingestion.MinioBlobStore;
import com.yunjihai.rag.ingestion.OrphanBlobRecorder;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties(MinioClientProperties.class)
public class MinioAdapterConfiguration {

    @Bean(destroyMethod = "close")
    MinioClient minioClient(MinioClientProperties properties) {
        return properties.client();
    }

    @Bean(destroyMethod = "close")
    MinioAsyncClient minioAsyncClient(MinioClientProperties properties) {
        return properties.asyncClient();
    }

    @Bean
    SdkMinioObjectClient minioObjectClient(MinioAsyncClient client) {
        return new SdkMinioObjectClient(client);
    }

    @Bean
    BlobStore blobStore(
            MinioClientProperties properties,
            SdkMinioObjectClient objects,
            OrphanBlobRecorder orphanRecorder) {
        return new MinioBlobStore(properties.bucket(), objects, orphanRecorder);
    }

    @Bean
    MinioBlobReader minioBlobReader(MinioClient client, MinioClientProperties properties) {
        return new MinioBlobReader(client, properties.bucket());
    }

    @Bean(name = "minio")
    MinioReadinessHealthIndicator minioReadinessHealthIndicator(
            MinioClient client,
            MinioClientProperties properties) {
        return new MinioReadinessHealthIndicator(client, properties.bucket());
    }
}
