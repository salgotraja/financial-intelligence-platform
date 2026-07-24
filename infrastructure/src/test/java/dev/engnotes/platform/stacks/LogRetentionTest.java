package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;

// Pins CloudWatch Logs retention at 14 days across every Lambda log group in the Ingestion, Query,
// and Security stacks (was RetentionDays.ONE_MONTH / 30 days).
class LogRetentionTest {

    @Test
    @SuppressWarnings("unchecked")
    void allLogGroupsRetainFourteenDays() {
        for (Template template : templatesForAllStacks()) {
            Map<String, Map<String, Object>> logGroups = template.findResources("AWS::Logs::LogGroup");
            assertFalse(logGroups.isEmpty(), "expected at least one log group to synth");
            logGroups.forEach((id, resource) -> {
                var props = (Map<String, Object>) resource.get("Properties");
                assertEquals(14, props.get("RetentionInDays"), "log group " + id + " retention");
            });
        }
    }

    private static List<Template> templatesForAllStacks() {
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
        return List.of(Template.fromStack(ingestion), Template.fromStack(query), Template.fromStack(security));
    }
}
