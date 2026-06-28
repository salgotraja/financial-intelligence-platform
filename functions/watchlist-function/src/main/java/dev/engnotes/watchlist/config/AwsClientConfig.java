package dev.engnotes.watchlist.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AWS SDK v2 client for the watchlist write path. Region comes from AWS_REGION in Lambda (default
 * ap-south-1 for local/tests). UrlConnectionHttpClient keeps the JAR small. Credentials resolve
 * lazily via the default provider chain, so the bean constructs without contacting AWS at startup.
 */
@Configuration
public class AwsClientConfig {

    @Value("${aws.region:ap-south-1}")
    private String region;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
