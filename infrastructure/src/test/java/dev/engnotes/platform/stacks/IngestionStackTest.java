package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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

    @Test
    void correlationsFunctionSelectsTheComputeCorrelationsBean() {
        synth().hasResourceProperties(
                        "AWS::Lambda::Function",
                        Match.objectLike(Map.of(
                                "FunctionName",
                                "financial-correlations-dev",
                                "Environment",
                                Map.of(
                                        "Variables",
                                        Match.objectLike(Map.of(
                                                "SPRING_CLOUD_FUNCTION_DEFINITION", "computeCorrelations",
                                                "MAIN_CLASS", "dev.engnotes.insight.InsightHandler"))))));
    }

    @Test
    void correlationsScheduleFiresEvery15MinutesInsideTheSessionWindowWithASourceDiscriminator() {
        synth().hasResourceProperties(
                        "AWS::Events::Rule",
                        Match.objectLike(Map.of(
                                "ScheduleExpression",
                                "cron(0/15 3-9 ? * MON-FRI *)",
                                "Targets",
                                Match.arrayWith(List.of(
                                        Match.objectLike(Map.of("Input", "{\"source\":\"eventbridge-schedule\"}")))))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void correlationsRoleGrantsOnlyPlatformTableAndKmsAccess() {
        Template template = synth();
        var roles = template.findResources("AWS::IAM::Role");
        String correlationsRoleLogicalId = roles.entrySet().stream()
                .filter(e -> {
                    var props = (Map<String, Object>) ((Map<String, Object>) e.getValue()).get("Properties");
                    return "financial-correlations-lambda-role-dev".equals(props.get("RoleName"));
                })
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a CorrelationsLambdaRole to synth"));

        var policies = template.findResources("AWS::IAM::Policy");
        List<String> actions = new ArrayList<>();
        for (var policy : policies.values()) {
            var props = (Map<String, Object>) policy.get("Properties");
            var roleRefs = (List<Object>) props.get("Roles");
            boolean attachedToCorrelationsRole = roleRefs.stream()
                    .map(r -> (Map<String, Object>) r)
                    .anyMatch(r -> correlationsRoleLogicalId.equals(r.get("Ref")));
            if (!attachedToCorrelationsRole) {
                continue;
            }
            var doc = (Map<String, Object>) props.get("PolicyDocument");
            var statements = (List<Object>) doc.get("Statement");
            for (Object statementObj : statements) {
                var statement = (Map<String, Object>) statementObj;
                Object actionValue = statement.get("Action");
                if (actionValue instanceof List<?> actionList) {
                    actionList.forEach(a -> actions.add((String) a));
                } else if (actionValue instanceof String actionString) {
                    actions.add(actionString);
                }
            }
        }

        // dynamodb/kms come from the table read/write and CMK grants under test; xray is the tracing
        // write access every ingestion-adjacent Lambda role carries (AWSXRayDaemonWriteAccess), not a
        // data-plane grant. bedrock/s3/secretsmanager are the ones this test exists to keep off this role.
        assertFalse(actions.isEmpty(), "expected the correlations role to carry at least one grant");
        for (String action : actions) {
            String service = action.substring(0, action.indexOf(':'));
            assertTrue(
                    service.equals("dynamodb") || service.equals("kms") || service.equals("xray"),
                    "expected only dynamodb/kms/xray actions on the correlations role, found: " + action);
        }
    }
}
