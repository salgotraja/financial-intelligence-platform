package dev.engnotes.dsr.config;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AWS SDK v2 clients for the DSR path. Region comes from AWS_REGION in Lambda (default ap-south-1 for
 * local/tests). UrlConnectionHttpClient keeps the JAR small. Credentials resolve lazily via the
 * default provider chain, so beans construct without contacting AWS at startup. The {@link Clock} bean
 * makes audit timestamps deterministic in tests.
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

    @Bean
    public CognitoIdentityProviderClient cognitoClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
