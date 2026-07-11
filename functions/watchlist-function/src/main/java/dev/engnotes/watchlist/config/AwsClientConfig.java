package dev.engnotes.watchlist.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** AWS SDK v2 client for the watchlist write path. aws.endpoint-url blank in prod, set in tests. */
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

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return withEndpoint(DynamoDbClient.builder())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
