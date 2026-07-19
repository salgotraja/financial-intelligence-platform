package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;

/**
 * ADR 0004: the platform runs entirely outside a VPC. No stack may create a VPC or place a Lambda
 * in one; a reintroduction must be a deliberate decision, not a copy-paste accident.
 *
 * <p>Follows the {@link PlatformWideHardeningTest} pattern: construct every app stack once in a
 * single App (CDK forbids modifying the construct tree after the first {@code Template.fromStack()}
 * call), then synthesize each into a template and run the assertion over all of them.
 */
class NoVpcRegressionTest {

    @Test
    void noStackCreatesAVpcOrVpcPlacedLambda() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();

        var data = new DataStack(app, "Data", props, "dev");
        var security = new SecurityStack(app, "Security", props, "dev", data);
        var ingestion = new IngestionStack(app, "Ingestion", props, "dev", data);
        var query = new QueryStack(app, "Query", props, "dev", data, ingestion, security);

        Map<String, Template> templatesByStack = new LinkedHashMap<>();
        templatesByStack.put("Data", Template.fromStack(data));
        templatesByStack.put("Security", Template.fromStack(security));
        templatesByStack.put("Ingestion", Template.fromStack(ingestion));
        templatesByStack.put("Query", Template.fromStack(query));

        for (var entry : templatesByStack.entrySet()) {
            Template template = entry.getValue();
            template.resourceCountIs("AWS::EC2::VPC", 0);
            template.resourceCountIs("AWS::EC2::NatGateway", 0);
            template.resourceCountIs("AWS::EC2::VPCEndpoint", 0);
            template.findResources("AWS::Lambda::Function").values().forEach(fn -> {
                @SuppressWarnings("unchecked")
                var fnProperties = (Map<String, Object>) fn.get("Properties");
                assertFalse(
                        fnProperties.containsKey("VpcConfig"),
                        "stack " + entry.getKey() + " has a VPC-placed Lambda function");
            });
        }
    }
}
