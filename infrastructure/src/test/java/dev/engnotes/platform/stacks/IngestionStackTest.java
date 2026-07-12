package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class IngestionStackTest {

    private static Template synth() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();
        var data = new DataStack(app, "Data", props, "dev");
        var network = new NetworkStack(app, "Network", props, "dev");
        var ingestion = new IngestionStack(app, "Ingestion", props, "dev", network, data);
        return Template.fromStack(ingestion);
    }

    @Test
    void hasPipelineFailedAlarm() {
        synth().hasResourceProperties(
                        "AWS::CloudWatch::Alarm",
                        Match.objectLike(Map.of("AlarmName", "financial-ingestion-pipeline-failed-dev")));
    }

    @Test
    void hasDlqDepthAlarm() {
        synth().hasResourceProperties(
                        "AWS::CloudWatch::Alarm",
                        Match.objectLike(Map.of("AlarmName", "financial-ingestion-dlq-depth-dev")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void itemSelectorMapsExecutionInputSourceForBothTriggerPaths() {
        var machines = synth().findResources("AWS::StepFunctions::StateMachine").values();
        assertFalse(machines.isEmpty(), "expected the ingestion state machine to synth");

        var joinedDefinition = machines.stream()
                .map(m -> (Map<String, Object>) ((Map<String, Object>) m).get("Properties"))
                .map(p -> (Map<String, Object>) p.get("DefinitionString"))
                .map(d -> (List<Object>) d.get("Fn::Join"))
                .flatMap(parts -> ((List<Object>) parts.get(1)).stream())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.joining());

        assertTrue(
                joinedDefinition.contains("\"source.$\":\"$$.Execution.Input.source\""),
                "expected the Distributed Map's itemSelector to carry source.$ from the execution input");
    }
}
