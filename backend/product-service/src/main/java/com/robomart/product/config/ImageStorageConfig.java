package com.robomart.product.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageStorageProperties.class)
public class ImageStorageConfig {
    @Bean(destroyMethod = "close")
    S3Client imageS3Client(ImageStorageProperties properties) {
        return createClient(properties);
    }

    /** Also used by the explicit migration CLI, without starting Spring or any application bean. */
    public static S3Client createClient(ImageStorageProperties properties) {
        if (!properties.isCredentialPairValid()) throw new IllegalArgumentException("Incomplete S3 credentials");
        var builder = S3Client.builder().region(Region.of(properties.getRegion()))
                .forcePathStyle(properties.isPathStyle())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5)));
        if (!properties.getAccessKey().isBlank()) builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())));
        if (!properties.getEndpoint().isBlank()) builder.endpointOverride(URI.create(properties.getEndpoint()));
        return builder.build();
    }
}
