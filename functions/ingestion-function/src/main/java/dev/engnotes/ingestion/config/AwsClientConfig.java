package dev.engnotes.ingestion.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * AWS SDK v2 clients.
 *
 * Region comes from AWS_REGION in Lambda (default ap-south-1 for local/tests).
 * UrlConnectionHttpClient keeps the deployment JAR small. Credentials resolve
 * lazily via the default provider chain, so these beans construct without
 * contacting AWS at startup (the Spring context loads in tests with no creds).
 *
 * aws.endpoint-url is normally blank (prod/Lambda); integration tests set it to a
 * LocalStack endpoint to redirect every client. Blank means no override.
 */
@Configuration
public class AwsClientConfig {

    @Value("${aws.region:ap-south-1}")
    private String region;

    @Value("${aws.endpoint-url:}")
    private String endpointUrl;

    private <B extends AwsClientBuilder<B, ?>> B withEndpoint(B builder) {
        builder.region(Region.of(region));
        if (!endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder;
    }

    /** Shared JDK HttpClient for market-data providers; one per Lambda instance, reused across calls. */
    @Bean
    public HttpClient marketDataHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return withEndpoint(SecretsManagerClient.builder())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return withEndpoint(DynamoDbClient.builder())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Bean
    public S3Client s3Client() {
        var builder = withEndpoint(S3Client.builder()).httpClient(UrlConnectionHttpClient.create());
        if (!endpointUrl.isBlank()) {
            builder.forcePathStyle(true);
        }
        return builder.build();
    }
}
