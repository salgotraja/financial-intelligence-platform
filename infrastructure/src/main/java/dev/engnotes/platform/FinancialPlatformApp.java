package dev.engnotes.platform;

import dev.engnotes.platform.stacks.FoundationStack;
import dev.engnotes.platform.stacks.IngestionStack;
import dev.engnotes.platform.stacks.QueryStack;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * CDK application entry point. Stacks (FoundationStack, IngestionStack, QueryStack) are
 * wired here as each infrastructure module is built out per the platform roadmap.
 */
public class FinancialPlatformApp {

    public static void main(String[] args) {
        App app = new App();

        String env = (String) app.getNode().tryGetContext("env");
        app.getNode().setContext("env", env);

        Environment awsEnv = Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build();

        StackProps props = StackProps.builder().env(awsEnv).build();

        // Stack 1 - Foundation (VPC, KMS, DynamoDB, S3, IAM)
        FoundationStack foundation = new FoundationStack(app, "FinancialPlatform-Foundation-" + env, props, env);

        // Stack 2 - Ingestion (EventBridge, Step Functions, Lambda)
        IngestionStack ingestion =
                new IngestionStack(app, "FinancialPlatform-Ingestion-" + env, props, env, foundation);

        // Stack 3 - Query (API Gateway, query Lambda, DAX)
        QueryStack query = new QueryStack(app, "FinancialPlatform-Query-" + env, props, env, foundation);
        query.addDependency(foundation);

        app.synth();
    }
}
