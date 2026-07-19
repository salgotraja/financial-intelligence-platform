package dev.engnotes.platform.stacks;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;

/**
 * Task 4 hardening bundle, item 7: a platform-wide sibling to
 * {@code QueryStackTest#noSnapStartFunctionCombinesWithProvisionedConcurrency}, which only covers
 * QueryStack. This test runs the identical check - {@link LiveAliasAssertions}, so the assertion
 * logic is written exactly once and looped, not copy-pasted per stack - over every stack that can
 * define a Lambda function or alias (Data, Security, Ingestion, Query, Realtime) under
 * prod context. A provisioned-concurrency regression on any SnapStart function anywhere in the
 * platform now fails CI, not only on QueryStack.
 */
class PlatformWideHardeningTest {

    @Test
    void noSnapStartFunctionCombinesWithProvisionedConcurrencyAcrossStacks() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();

        // Construct every stack before synthesizing any of them: CDK forbids modifying the
        // construct tree after the first Template.fromStack() call in the same App.
        var data = new DataStack(app, "Data", props, "prod");
        var security = new SecurityStack(app, "Security", props, "prod", data);
        var ingestion = new IngestionStack(app, "Ingestion", props, "prod", data);
        var query = new QueryStack(app, "Query", props, "prod", data, ingestion, security);
        var realtime = new RealtimeStack(app, "Realtime", props, "prod", data, security);

        Map<String, Template> templatesByStack = new LinkedHashMap<>();
        templatesByStack.put("Data", Template.fromStack(data));
        templatesByStack.put("Security", Template.fromStack(security));
        templatesByStack.put("Ingestion", Template.fromStack(ingestion));
        templatesByStack.put("Query", Template.fromStack(query));
        templatesByStack.put("Realtime", Template.fromStack(realtime));

        for (var entry : templatesByStack.entrySet()) {
            LiveAliasAssertions.assertNoSnapStartFunctionCombinesWithProvisionedConcurrency(
                    entry.getKey(), entry.getValue());
        }
    }
}
