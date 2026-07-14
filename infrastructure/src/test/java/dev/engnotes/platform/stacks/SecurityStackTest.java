package dev.engnotes.platform.stacks;

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
                                                "v1.0")))))));
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
}
