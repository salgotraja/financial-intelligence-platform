package dev.engnotes.platform;

import dev.engnotes.platform.stacks.DataStack;
import dev.engnotes.platform.stacks.IngestionStack;
import dev.engnotes.platform.stacks.NetworkStack;
import dev.engnotes.platform.stacks.QueryStack;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * CDK application entry point.
 *
 * <p>Stacks are split stateful-vs-ephemeral so the costly infra can be torn down between sessions
 * without losing data: DataStack (KMS, DynamoDB, S3, SNS) stays deployed; NetworkStack (VPC, NAT,
 * endpoints), IngestionStack, and QueryStack are the teardown set. See USER-GUIDE.md "Cost control".
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

        // Stateful stack - stays deployed across sessions (KMS, DynamoDB, S3, SNS).
        DataStack data = new DataStack(app, "FinancialPlatform-Data-" + env, props, env);

        // Ephemeral stack - VPC, NAT, endpoints. Torn down between sessions to save idle cost.
        NetworkStack network = new NetworkStack(app, "FinancialPlatform-Network-" + env, props, env);

        // Ingestion (EventBridge, Step Functions, Lambda) - depends on both halves.
        IngestionStack ingestion =
                new IngestionStack(app, "FinancialPlatform-Ingestion-" + env, props, env, network, data);

        // Query (API Gateway, query + watchlist Lambdas) - depends on network, data, and ingestion.
        new QueryStack(app, "FinancialPlatform-Query-" + env, props, env, network, data, ingestion);

        app.synth();
    }
}
