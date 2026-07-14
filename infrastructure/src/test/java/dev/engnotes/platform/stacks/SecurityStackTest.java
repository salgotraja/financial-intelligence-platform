package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class SecurityStackTest {

    static Template synth() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();
        var data = new DataStack(app, "Data", props, "dev");
        var security = new SecurityStack(app, "Security", props, "dev", data);
        return Template.fromStack(security);
    }

    // Task 9: PreAuthentication login gate, same jar as postConfirmation, its own function
    // definition, non-VPC.
    @Test
    void hasPreAuthenticationLambdaServingConsentGate() {
        synth().hasResourceProperties(
                        "AWS::Lambda::Function",
                        Match.objectLike(Map.of(
                                "FunctionName",
                                "financial-preauthentication-dev",
                                "Environment",
                                Match.objectLike(Map.of(
                                        "Variables",
                                        Match.objectLike(Map.of(
                                                "SPRING_CLOUD_FUNCTION_DEFINITION",
                                                "preAuthentication",
                                                "CONSENT_POLICY_VERSION",
                                                "v1")))))));
    }

    @Test
    void userPoolWiresBothPostConfirmationAndPreAuthenticationTriggers() {
        synth().hasResourceProperties(
                        "AWS::Cognito::UserPool",
                        Match.objectLike(Map.of(
                                "LambdaConfig",
                                Match.objectLike(Map.of(
                                        "PostConfirmation", Match.anyValue(),
                                        "PreAuthentication", Match.anyValue())))));
    }

    // Least privilege: the login gate only ever reads the CONSENT item and appends
    // CONSENT_RECONSENT_REQUIRED audit events, so its role must hold GetItem on the platform table
    // and PutItem on the audit table, and nothing that can mutate the platform table.
    @Test
    @SuppressWarnings("unchecked")
    void preAuthenticationRoleIsReadOnlyOnPlatformTableAndAppendOnlyOnAuditTable() {
        var policies = synth().findResources(
                        "AWS::IAM::Policy",
                        Match.objectLike(Map.of(
                                "Properties",
                                Match.objectLike(Map.of(
                                        "PolicyName",
                                        Match.stringLikeRegexp("PreAuthenticationLambdaRoleDefaultPolicy.*"))))));
        assertEquals(1, policies.size(), "expected exactly one PreAuthentication role default policy");

        var props = (Map<String, Object>) policies.values().iterator().next().get("Properties");
        var document = (Map<String, Object>) props.get("PolicyDocument");
        var statements = (List<Map<String, Object>>) document.get("Statement");

        boolean platformGetItem = false;
        boolean auditPutItem = false;
        for (var statement : statements) {
            List<String> actions = actionsOf(statement);
            String resources = String.valueOf(statement.get("Resource"));
            if (resources.contains("PlatformTable")) {
                platformGetItem |= actions.contains("dynamodb:GetItem");
                for (var forbidden : List.of("dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem")) {
                    assertFalse(
                            actions.contains(forbidden),
                            "PreAuthentication role must not hold " + forbidden + " on the platform table");
                }
            }
            if (resources.contains("AuditTable")) {
                auditPutItem |= actions.contains("dynamodb:PutItem");
            }
        }
        assertTrue(platformGetItem, "expected dynamodb:GetItem on the platform table");
        assertTrue(auditPutItem, "expected dynamodb:PutItem on the audit table");
    }

    @SuppressWarnings("unchecked")
    private static List<String> actionsOf(Map<String, Object> statement) {
        Object action = statement.get("Action");
        return action instanceof List ? (List<String>) action : List.of(String.valueOf(action));
    }
}
