package dev.engnotes.insight.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AwsEndpointOverrideTest {

    private BedrockConfig configWith(String endpointUrl) {
        BedrockConfig config = new BedrockConfig();
        ReflectionTestUtils.setField(config, "region", "ap-south-1");
        ReflectionTestUtils.setField(config, "endpointUrl", endpointUrl);
        return config;
    }

    @Test
    void dynamoClientUsesEndpointOverrideWhenSet() {
        try (var client = configWith("http://localhost:4566").dynamoDbClient()) {
            assertThat(client.serviceClientConfiguration().endpointOverride())
                    .contains(URI.create("http://localhost:4566"));
        }
    }

    @Test
    void dynamoClientUsesDefaultEndpointWhenBlank() {
        try (var client = configWith("").dynamoDbClient()) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void bedrockClientUsesEndpointOverrideWhenSet() {
        try (var client = configWith("http://localhost:4566").bedrockRuntimeClient()) {
            assertThat(client.serviceClientConfiguration().endpointOverride())
                    .contains(URI.create("http://localhost:4566"));
        }
    }
}
