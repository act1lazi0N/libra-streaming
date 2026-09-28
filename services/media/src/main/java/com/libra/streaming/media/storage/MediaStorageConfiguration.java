package com.libra.streaming.media.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@ConditionalOnProperty(prefix = "libra.media.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MediaStorageProperties.class)
class MediaStorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client mediaS3Client(MediaStorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(properties.internalEndpointUri())
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(properties.connectTimeout())
                        .socketTimeout(properties.requestTimeout()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(properties.requestTimeout())
                        .apiCallAttemptTimeout(properties.requestTimeout())
                        .build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .forcePathStyle(true)
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner mediaS3Presigner(MediaStorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(properties.browserEndpointUri())
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    StagingUploadSigner stagingUploadSigner(S3Presigner presigner, MediaStorageProperties properties) {
        return new StagingUploadSigner(presigner, properties);
    }

    @Bean
    MediaStorageHealthIndicator mediaStorageHealthIndicator(S3Client client, MediaStorageProperties properties) {
        return new MediaStorageHealthIndicator(client, properties);
    }

    @Bean
    S3MediaStorage mediaStorage(S3Client client, MediaStorageProperties properties) throws java.io.IOException {
        return new S3MediaStorage(client, properties);
    }

    @Bean
    MediaStorageStartup mediaStorageStartup(S3MediaStorage storage, S3Client client,
            MediaStorageProperties properties) {
        return new MediaStorageStartup(storage, client, properties);
    }
}
