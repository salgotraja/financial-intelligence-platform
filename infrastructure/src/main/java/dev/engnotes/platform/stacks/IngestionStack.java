package dev.engnotes.platform.stacks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.events.*;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.events.targets.SfnStateMachine;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.amazon.awscdk.services.stepfunctions.*;
import software.amazon.awscdk.services.stepfunctions.tasks.*;
import software.constructs.Construct;

/**
 * Ingestion Stack.
 * <p>
 * Pipeline (per docs/requirement.md - four states wired in sequence):
 *   EventBridge (prod: NSE market hours; dev: every 5 min) -> Step Functions
 *       -> ValidateInput (Pass: shapes the EventBridge payload)
 *       -> FetchMarketData (Lambda: live market data from provider API)
 *       -> GenerateInsight (Lambda: Bedrock Claude insight)
 *       -> ExecutionSucceeded
 *   On any state failure: catch -> SendToDlq (SQS) -> ExecutionFailed.
 * <p>
 * Roadmap (later modules, each needs its own Lambda): CheckInsightCache, StoreResults, NotifyComplete.
 */
public class IngestionStack extends Stack {

    public IngestionStack(
            final Construct scope,
            final String id,
            final StackProps props,
            final String env,
            final FoundationStack foundation) {
        super(scope, id, props);

        // Deploy Foundation (VPC, KMS, tables, bucket) before this stack.
        this.addDependency(foundation);

        // == Dead Letter Queue ==
        // The catch path publishes failed executions here; the EventBridge target
        // also lands start-failures here. In prod a Lambda subscriber pages on-call.
        Queue dlq = Queue.Builder.create(this, "IngestionDLQ")
                .queueName("financial-ingestion-dlq-" + env)
                .encryption(QueueEncryption.KMS)
                .encryptionMasterKey(foundation.getEncryptionKey())
                .retentionPeriod(Duration.days(14))
                .build();

        // == IAM Role for ingestion Lambdas ==
        // Single shared role, least privilege via specific resource ARNs (no wildcards).
        Role ingestionRole = Role.Builder.create(this, "IngestionLambdaRole")
                .roleName("financial-ingestion-lambda-role-" + env)
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();

        // DynamoDB read/write on the two specific tables only.
        foundation.getMarketDataTable().grantReadWriteData(ingestionRole);
        foundation.getInsightTable().grantReadWriteData(ingestionRole);
        // S3 write on the data lake bucket only.
        foundation.getDataLakeBucket().grantWrite(ingestionRole);
        // KMS encrypt/decrypt for DynamoDB and S3.
        foundation.getEncryptionKey().grantEncryptDecrypt(ingestionRole);

        // Bedrock invoke. Sonnet 4.5 is INFERENCE_PROFILE-only in ap-south-1 (verified):
        // the bare foundation-model id is not invocable on demand. We call the global
        // cross-region profile, so the policy must allow BOTH the inference-profile ARN
        // and the underlying foundation-model ARN (note the -v1:0 version suffix). Global
        // profiles can route to any region, hence the * region on the foundation-model.
        ingestionRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel"))
                .resources(List.of(
                        "arn:aws:bedrock:" + this.getRegion() + ":" + this.getAccount()
                                + ":inference-profile/global.anthropic.claude-sonnet-4-5-20250929-v1:0",
                        "arn:aws:bedrock:*::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0"))
                .build());

        // Secrets Manager read - market data provider API key path only.
        ingestionRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("secretsmanager:GetSecretValue"))
                .resources(List.of("arn:aws:secretsmanager:" + this.getRegion() + ":" + this.getAccount()
                        + ":secret:financial-platform/*"))
                .build());

        // == Common Lambda environment ==
        // Keys must NOT start with AWS_ - Lambda reserves that prefix and rejects the deploy.
        Map<String, String> commonEnvVars = Map.of(
                "MARKET_DATA_TABLE",
                foundation.getMarketDataTable().getTableName(),
                "INSIGHT_TABLE",
                foundation.getInsightTable().getTableName(),
                "DATA_LAKE_BUCKET",
                foundation.getDataLakeBucket().getBucketName(),
                "ENVIRONMENT",
                env,
                "POWERTOOLS_SERVICE_NAME",
                "financial-intelligence-platform",
                "LOG_LEVEL",
                env.equals("prod") ? "INFO" : "DEBUG");

        // == Fetch Market Data Lambda ==
        // SnapStart removes Spring Boot cold start (3-8s -> <200ms) on published versions.
        Map<String, String> fetchEnv = new HashMap<>(commonEnvVars);
        fetchEnv.put("SPRING_CLOUD_FUNCTION_DEFINITION", "fetchMarketData");
        fetchEnv.put("MARKET_DATA_API_SECRET", "financial-platform/market-data-api-key");

        Function fetchMarketDataFn = Function.Builder.create(this, "FetchMarketDataFn")
                .functionName("financial-fetch-market-data-" + env)
                .description("Fetches live market data from NSE/BSE provider APIs")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker")
                .code(Code.fromAsset("../functions/ingestion-function/target/ingestion-function.jar"))
                .role(ingestionRole)
                .memorySize(512)
                .timeout(Duration.seconds(30))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(fetchEnv)
                .vpc(foundation.getVpc())
                .build();

        // SnapStart applies only to a published version, so invoke an alias - not $LATEST.
        Alias fetchAlias = Alias.Builder.create(this, "FetchMarketDataAlias")
                .aliasName("live")
                .version(fetchMarketDataFn.getCurrentVersion())
                .build();

        LogGroup.Builder.create(this, "FetchMarketDataLogs")
                .logGroupName("/aws/lambda/" + fetchMarketDataFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // == Generate Insight Lambda ==
        // Higher memory: Bedrock response parsing benefits from it.
        // TODO(module-4): the insight-function still needs its `generateInsight` bean.
        Map<String, String> insightEnv = new HashMap<>(commonEnvVars);
        insightEnv.put("SPRING_CLOUD_FUNCTION_DEFINITION", "generateInsight");
        // Sonnet 4.5 is INFERENCE_PROFILE-only in ap-south-1; invoke the global profile id,
        // not the bare foundation-model id (the latter returns ValidationException on demand).
        insightEnv.put("BEDROCK_MODEL_ID", "global.anthropic.claude-sonnet-4-5-20250929-v1:0");
        insightEnv.put("BEDROCK_MAX_TOKENS", "1024");

        Function generateInsightFn = Function.Builder.create(this, "GenerateInsightFn")
                .functionName("financial-generate-insight-" + env)
                .description("Generates LLM-powered financial insights via AWS Bedrock")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker")
                .code(Code.fromAsset("../functions/insight-function/target/insight-function.jar"))
                .role(ingestionRole)
                .memorySize(1024)
                .timeout(Duration.seconds(60))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(insightEnv)
                // Run in the VPC so Bedrock is reached over the PrivateLink interface
                // endpoint (FoundationStack BedrockRuntimeEndpoint), not the public internet.
                .vpc(foundation.getVpc())
                .build();

        Alias insightAlias = Alias.Builder.create(this, "GenerateInsightAlias")
                .aliasName("live")
                .version(generateInsightFn.getCurrentVersion())
                .build();

        LogGroup.Builder.create(this, "GenerateInsightLogs")
                .logGroupName("/aws/lambda/" + generateInsightFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // == Step Functions Workflow ==
        // Retry: 3 attempts, 2s base, 2x backoff, full jitter (per requirement.md).
        RetryProps standardRetry = RetryProps.builder()
                .errors(List.of(
                        "Lambda.ServiceException",
                        "Lambda.AWSLambdaException",
                        "Lambda.SdkClientException",
                        "Lambda.TooManyRequestsException",
                        "States.TaskFailed"))
                .interval(Duration.seconds(2))
                .maxAttempts(3)
                .backoffRate(2.0)
                .jitterStrategy(JitterType.FULL)
                .build();

        // Terminal states.
        Succeed executionSucceeded = Succeed.Builder.create(this, "ExecutionSucceeded")
                .comment("Pipeline completed successfully")
                .build();

        Fail executionFailed = Fail.Builder.create(this, "ExecutionFailed")
                .comment("Pipeline failed after retries")
                .error("PipelineError")
                .build();

        // Failure path: publish the error context to the DLQ, then fail the execution.
        SqsSendMessage sendToDlq = SqsSendMessage.Builder.create(this, "SendToDlq")
                .queue(dlq)
                .messageBody(TaskInput.fromJsonPathAt("$"))
                .comment("Publish failed-execution context to the DLQ")
                .build();
        sendToDlq.next(executionFailed);

        CatchProps catchToDlq =
                CatchProps.builder().errors(List.of("States.ALL")).build();

        // State 1: validate the EventBridge payload shape (Pass).
        Pass validateInput = Pass.Builder.create(this, "ValidateInput")
                .comment("Validates EventBridge payload shape before any Lambda runs.")
                .parameters(Map.of(
                        "ticker.$", "$.ticker",
                        "requestedAt.$", "$$.Execution.StartTime",
                        "correlationId.$", "$$.Execution.Id"))
                .build();

        // State 2: fetch market data.
        LambdaInvoke fetchMarketData = LambdaInvoke.Builder.create(this, "FetchMarketData")
                .lambdaFunction(fetchAlias)
                .comment("Fetch live market data from provider API")
                .payloadResponseOnly(true)
                .retryOnServiceExceptions(false) // replaced by standardRetry
                .build();
        fetchMarketData.addRetry(standardRetry);
        fetchMarketData.addCatch(sendToDlq, catchToDlq);

        // State 3: generate insight.
        LambdaInvoke generateInsight = LambdaInvoke.Builder.create(this, "GenerateInsight")
                .lambdaFunction(insightAlias)
                .comment("Generate LLM insight via AWS Bedrock")
                .payloadResponseOnly(true)
                .retryOnServiceExceptions(false) // replaced by standardRetry
                .build();
        generateInsight.addRetry(standardRetry);
        generateInsight.addCatch(sendToDlq, catchToDlq);

        // Wire the success chain.
        Chain pipelineChain = Chain.start(validateInput)
                .next(fetchMarketData)
                .next(generateInsight)
                .next(executionSucceeded);

        StateMachine stateMachine = StateMachine.Builder.create(this, "IngestionStateMachine")
                .stateMachineName("financial-ingestion-pipeline-" + env)
                .definitionBody(DefinitionBody.fromChainable(pipelineChain))
                .tracingEnabled(true)
                .stateMachineType(StateMachineType.STANDARD)
                .logs(LogOptions.builder()
                        .destination(new LogGroup(
                                this,
                                "StateMachineLogs",
                                LogGroupProps.builder()
                                        .logGroupName("/aws/states/financial-ingestion-" + env)
                                        .retention(RetentionDays.ONE_MONTH)
                                        .removalPolicy(RemovalPolicy.DESTROY)
                                        .build()))
                        .level(LogLevel.ERROR)
                        .includeExecutionData(true)
                        .build())
                .build();

        // == EventBridge Rule ==
        // prod: every minute during NSE market hours (9:15-15:30 IST, the 3-10 UTC window).
        // dev: every 5 minutes.
        Rule.Builder.create(this, "MarketDataSchedule")
                .ruleName("financial-market-data-schedule-" + env)
                .description("Triggers the financial data pipeline (prod: NSE market hours; dev: every 5 min)")
                .schedule(Schedule.expression(env.equals("prod") ? "cron(*/1 3-10 ? * MON-FRI *)" : "rate(5 minutes)"))
                .targets(List.of(SfnStateMachine.Builder.create(stateMachine)
                        .input(RuleTargetInput.fromObject(Map.of(
                                "ticker", "RELIANCE.NS",
                                "source", "eventbridge-schedule")))
                        .deadLetterQueue(dlq)
                        .retryAttempts(2)
                        .build()))
                .build();

        // CloudFormation output.
        new CfnOutput(
                this,
                "StateMachineArn",
                CfnOutputProps.builder()
                        .exportName("platform-state-machine-arn-" + env)
                        .value(stateMachine.getStateMachineArn())
                        .build());
    }
}
