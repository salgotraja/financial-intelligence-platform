package dev.engnotes.platform.stacks;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class DataStackTest {

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
    void hasWarningAndCriticalTopics() {
        synth().resourceCountIs("AWS::SNS::Topic", 2);
    }

    @Test
    void hasMonthlyCostBudget() {
        var t = synth();
        t.resourceCountIs("AWS::Budgets::Budget", 1);
        var emailEntry = Match.objectLike(
                Map.of("Subscribers", Match.arrayWith(List.of(Match.objectLike(Map.of("SubscriptionType", "EMAIL"))))));
        t.hasResourceProperties(
                "AWS::Budgets::Budget",
                Match.objectLike(Map.of(
                        "Budget",
                                Match.objectLike(Map.of(
                                        "BudgetType", "COST",
                                        "TimeUnit", "MONTHLY",
                                        "BudgetLimit", Match.objectLike(Map.of("Amount", 5, "Unit", "USD")))),
                        "NotificationsWithSubscribers", Match.arrayWith(List.of(emailEntry, emailEntry, emailEntry)))));
    }

    @Test
    void platformTableHasNewImageStream() {
        synth().hasResourceProperties(
                        "AWS::DynamoDB::Table",
                        Match.objectLike(Map.of(
                                "TableName",
                                "financial-platform-dev",
                                "StreamSpecification",
                                Map.of("StreamViewType", "NEW_IMAGE"))));
    }

    // The erasure workflow's confirmation email (spec s11, Task 11) reuses this identity as its
    // sender, so it must default to the same alertEmail address the SNS topics already use.
    @Test
    void hasSesSenderIdentityForTheDefaultAlertEmail() {
        synth().hasResourceProperties(
                        "AWS::SES::EmailIdentity", Match.objectLike(Map.of("EmailIdentity", "alerts@engnotes.dev")));
    }
}
