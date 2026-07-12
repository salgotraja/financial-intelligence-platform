package dev.engnotes.notifier.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AWS SDK v2 clients for the realtime path. Region from AWS_REGION (default ap-south-1).
 * aws.endpoint-url is blank in prod; integration tests set it to LocalStack. The Management API
 * client MUST target the WebSocket stage callback URL (WS_CALLBACK_URL, set by RealtimeStack):
 * postToConnection is stage-scoped, not a regional endpoint.
 */
@Configuration
public class AwsClientConfig {

    @Value("${aws.region:ap-south-1}")
    private String region;

    @Value("${aws.endpoint-url:}")
    private String endpointUrl;

    @Value("${WS_CALLBACK_URL:}")
    private String wsCallbackUrl;

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

    @Bean
    public ApiGatewayManagementApiClient managementApiClient() {
        var builder = ApiGatewayManagementApiClient.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create());
        if (!wsCallbackUrl.isBlank()) {
            builder.endpointOverride(URI.create(wsCallbackUrl));
        } else if (!endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder.build();
    }
}
