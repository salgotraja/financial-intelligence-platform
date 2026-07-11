package dev.engnotes.query.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class AwsEndpointOverrideTest {

    private final AwsClientConfig config = new AwsClientConfig();

    @Test
    void appliesOverrideWhenEndpointUrlSet() {
        setField("region", "ap-south-1");
        setField("endpointUrl", "http://localhost:4566");
        try (DynamoDbClient ddb = config.dynamoDbClient()) {
            assertThat(ddb.serviceClientConfiguration().endpointOverride())
                    .contains(URI.create("http://localhost:4566"));
        }
    }

    @Test
    void noOverrideWhenEndpointUrlBlank() {
        setField("region", "ap-south-1");
        setField("endpointUrl", "");
        try (DynamoDbClient ddb = config.dynamoDbClient()) {
            assertThat(ddb.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    private void setField(String name, String value) {
        try {
            var f = AwsClientConfig.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(config, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
