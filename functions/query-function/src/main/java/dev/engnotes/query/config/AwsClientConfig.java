package dev.engnotes.query.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AWS SDK v2 clients for the read path.
 *
 * <p>Region comes from AWS_REGION in Lambda (default ap-south-1 for local/tests).
 * UrlConnectionHttpClient keeps the deployment JAR small. Credentials resolve lazily via the
 * default provider chain, so the bean constructs without contacting AWS at startup (the Spring
 * context loads in tests with no creds).
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
