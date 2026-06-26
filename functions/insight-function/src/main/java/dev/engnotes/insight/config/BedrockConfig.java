package dev.engnotes.insight.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AWS SDK v2 clients for the insight Lambda.
 *
 * Region comes from AWS_REGION in Lambda (default ap-south-1 for local/tests).
 * UrlConnectionHttpClient keeps the JAR small. Credentials resolve lazily via the
 * default provider chain, so these beans construct without contacting AWS at startup
 * (the Spring context loads in tests with no creds).
 *
 * Bedrock retries are disabled at the SDK level: the Step Functions standardRetry owns
 * retry and backoff, so SDK-level retries would only amplify cost on throttling. The API
 * call timeout sits under the 60s Lambda timeout so a hung model call fails the state
 * cleanly rather than timing out the whole invocation.
 */
@Configuration
public class BedrockConfig {

    @Value("${aws.region:ap-south-1}")
    private String region;

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(50))
                        .retryPolicy(RetryPolicy.none())
                        .build())
                .build();
    }

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
