package dev.engnotes.platform.stacks;

import dev.engnotes.itsupport.PlatformSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

/**
 * Fails if the DataStack DynamoDB schema diverges from the constants the integration-test
 * provisioner uses. Keeps emulated tables faithful to the real ones.
 */
class PlatformSchemaDriftTest {

    private static Template synth() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();
        return Template.fromStack(new DataStack(app, "Data", props, "dev"));
    }

    @Test
    void platformTableKeysAndGsiMatchProvisioner() {
        synth().hasResourceProperties(
                        "AWS::DynamoDB::Table",
                        Match.objectLike(Map.of(
                                "KeySchema",
                                        List.of(
                                                Map.of("AttributeName", PlatformSchema.PK, "KeyType", "HASH"),
                                                Map.of("AttributeName", PlatformSchema.SK, "KeyType", "RANGE")),
                                "GlobalSecondaryIndexes",
                                        Match.arrayWith(List.of(Match.objectLike(Map.of(
                                                "IndexName", PlatformSchema.GSI1_NAME,
                                                "KeySchema",
                                                        List.of(
                                                                Map.of(
                                                                        "AttributeName",
                                                                        PlatformSchema.GSI1_PK,
                                                                        "KeyType",
                                                                        "HASH"),
                                                                Map.of(
                                                                        "AttributeName",
                                                                        PlatformSchema.GSI1_SK,
                                                                        "KeyType",
                                                                        "RANGE")),
                                                "Projection", Map.of("ProjectionType", "ALL"))))),
                                "TimeToLiveSpecification",
                                        Map.of("AttributeName", PlatformSchema.TTL_ATTRIBUTE, "Enabled", true))));
    }

    @Test
    void schemaAttributeTypesAreAllStrings() {
        synth().hasResourceProperties(
                        "AWS::DynamoDB::Table",
                        Match.objectLike(Map.of(
                                "AttributeDefinitions",
                                Match.arrayWith(List.of(
                                        Map.of("AttributeName", PlatformSchema.PK, "AttributeType", "S"),
                                        Map.of("AttributeName", PlatformSchema.SK, "AttributeType", "S"),
                                        Map.of("AttributeName", PlatformSchema.GSI1_PK, "AttributeType", "S"),
                                        Map.of("AttributeName", PlatformSchema.GSI1_SK, "AttributeType", "S"))))));
    }
}
