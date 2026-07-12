package dev.engnotes.platform.stacks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.*;
import software.amazon.awscdk.customresources.AwsCustomResource;
import software.amazon.awscdk.customresources.AwsCustomResourcePolicy;
import software.amazon.awscdk.customresources.AwsSdkCall;
import software.amazon.awscdk.customresources.PhysicalResourceId;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.MetricOptions;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
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
 * Pipeline (spec sections 4-5):
 *   EventBridge (prod: NSE market hours; dev: every 5 min) -> Step Functions
 *       -> ReadWatchset (DynamoDB Query PK=WATCHSET: the distinct ticker union)
 *       -> FanOutTickers (Distributed Map, bounded concurrency, per-item retry + DLQ)
 *            each item: FetchMarketData (Lambda + z-score gate)
 *                       -> AnomalyGate (Choice: anomaly? -> GenerateInsight : InsightSkipped)
 *       -> ExecutionSucceeded
 *   Per-ticker failure: catch -> TickerToDlq (SQS) -> TickerFailed, isolated by the Map's
 *   tolerated-failure budget. Read/Map stage failure: catch -> SendToDlq (SQS) -> ExecutionFailed.
 * <p>
 * WATCHSET is seeded at deploy time (AwsCustomResource) until the watchlist write path maintains it.
 * Roadmap (later modules, each needs its own Lambda): CheckInsightCache, StoreResults, NotifyComplete.
 */
public class IngestionStack extends Stack {

    private final Queue dlq;
    private final StateMachine stateMachine;
    private final Alarm pipelineFailedAlarm;
    private final Alarm dlqDepthAlarm;

    public IngestionStack(
            final Construct scope,
            final String id,
            final StackProps props,
            final String env,
            final NetworkStack network,
            final DataStack data) {
        super(scope, id, props);

        // Deploy the VPC (network) and the table/key (data) before this stack.
        this.addDependency(network);
        this.addDependency(data);

        // == Dead Letter Queue ==
        // The catch path publishes failed executions here; the EventBridge target
        // also lands start-failures here. In prod a Lambda subscriber pages on-call.
        this.dlq = Queue.Builder.create(this, "IngestionDLQ")
                .queueName("financial-ingestion-dlq-" + env)
                .encryption(QueueEncryption.KMS)
                .encryptionMasterKey(data.getEncryptionKey())
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

        // DynamoDB read/write on the single platform table only.
        data.getPlatformTable().grantReadWriteData(ingestionRole);
        // S3 write on the data lake bucket only.
        data.getDataLakeBucket().grantWrite(ingestionRole);
        // KMS encrypt/decrypt for DynamoDB and S3.
        data.getEncryptionKey().grantEncryptDecrypt(ingestionRole);

        // Bedrock invoke. Claude is INFERENCE_PROFILE-only in ap-south-1: the bare
        // foundation-model id is not invocable on demand. We call the global cross-region
        // profile, so the policy must allow BOTH the inference-profile ARN and the underlying
        // foundation-model ARN. Global profiles can route to any region, hence the * region on
        // the foundation-model. Model verified against `aws bedrock list-inference-profiles`
        // in ap-south-1 (2026-07-12): global.anthropic.claude-sonnet-4-6 exists; the previously
        // configured global sonnet-4-5 profile does not.
        ingestionRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel"))
                .resources(List.of(
                        "arn:aws:bedrock:" + this.getRegion() + ":" + this.getAccount()
                                + ":inference-profile/global.anthropic.claude-sonnet-4-6",
                        "arn:aws:bedrock:*::foundation-model/anthropic.claude-sonnet-4-6"))
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
                "PLATFORM_TABLE",
                data.getPlatformTable().getTableName(),
                "DATA_LAKE_BUCKET",
                data.getDataLakeBucket().getBucketName(),
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
        // FunctionInvoker locates the @SpringBootApplication via MAIN_CLASS (the shaded uber-JAR has
        // no Boot Start-Class manifest entry).
        fetchEnv.put("MAIN_CLASS", "dev.engnotes.ingestion.IngestionHandler");
        fetchEnv.put("MARKET_DATA_API_SECRET", "financial-platform/market-data-api-key");
        // Anomaly-gate tunables (spec section 6), set to the AnomalyDetectionService code defaults so
        // they are operable without a redeploy. ANOMALY_MIN_SAMPLES gates the z-score until the
        // baseline has enough history; ANOMALY_Z_THRESHOLD is the standard-deviation trip point.
        fetchEnv.put("ANOMALY_Z_THRESHOLD", "3.0");
        fetchEnv.put("ANOMALY_MIN_SAMPLES", "5");

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
                .vpc(network.getVpc())
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
        // Invoked only when the anomaly gate routes here (see the Choice state below).
        Map<String, String> insightEnv = new HashMap<>(commonEnvVars);
        insightEnv.put("SPRING_CLOUD_FUNCTION_DEFINITION", "generateInsight");
        insightEnv.put("MAIN_CLASS", "dev.engnotes.insight.InsightHandler");
        // Claude is INFERENCE_PROFILE-only in ap-south-1; invoke the global profile id,
        // not the bare foundation-model id (the latter returns ValidationException on demand).
        insightEnv.put("BEDROCK_MODEL_ID", "global.anthropic.claude-sonnet-4-6");
        insightEnv.put("BEDROCK_MAX_TOKENS", "1024");
        // Cost tracking + daily-spend circuit breaker (spec section 9). Claude Sonnet list pricing
        // (USD per 1K tokens); the breaker opens once a day's spend reaches the cap and routes to
        // the rule-based fallback. Cost records share the insight table (already granted to the role).
        insightEnv.put("COST_DAILY_CAP_USD", "5.0");
        insightEnv.put("BEDROCK_INPUT_PRICE_PER_1K", "0.003");
        insightEnv.put("BEDROCK_OUTPUT_PRICE_PER_1K", "0.015");
        // Rule-based fallback tunables (spec section 9), set to the RuleBasedInsightGenerator code
        // defaults so the static-threshold signal and its (deliberately lower) confidence are
        // operable without a redeploy.
        insightEnv.put("RULE_BULLISH_THRESHOLD_PERCENT", "1.0");
        insightEnv.put("RULE_FALLBACK_CONFIDENCE", "0.4");

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
                .vpc(network.getVpc())
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

        // == Per-ticker pipeline (the Distributed Map item processor) ==
        // Each map iteration runs fetch -> anomaly gate -> (insight | skip) for one ticker. An
        // item-level failure is isolated: it goes to the DLQ and fails only that ticker, not the run.
        SqsSendMessage tickerToDlq = SqsSendMessage.Builder.create(this, "TickerToDlq")
                .queue(dlq)
                .messageBody(TaskInput.fromJsonPathAt("$"))
                .comment("Publish a failed ticker's context to the DLQ")
                .build();
        tickerToDlq.next(Fail.Builder.create(this, "TickerFailed")
                .error("TickerError")
                .comment("One ticker failed after retries; isolated from the rest of the fan-out")
                .build());
        CatchProps tickerCatch =
                CatchProps.builder().errors(List.of("States.ALL")).build();

        // Fetch market data for one ticker.
        LambdaInvoke fetchMarketData = LambdaInvoke.Builder.create(this, "FetchMarketData")
                .lambdaFunction(fetchAlias)
                .comment("Fetch live market data from provider API")
                .payloadResponseOnly(true)
                .retryOnServiceExceptions(false) // replaced by standardRetry
                .build();
        fetchMarketData.addRetry(standardRetry);
        fetchMarketData.addCatch(tickerToDlq, tickerCatch);

        // Generate insight (gated below).
        LambdaInvoke generateInsight = LambdaInvoke.Builder.create(this, "GenerateInsight")
                .lambdaFunction(insightAlias)
                .comment("Generate LLM insight via AWS Bedrock")
                .payloadResponseOnly(true)
                .retryOnServiceExceptions(false) // replaced by standardRetry
                .build();
        generateInsight.addRetry(standardRetry);
        generateInsight.addCatch(tickerToDlq, tickerCatch);

        // Anomaly gate: Bedrock is expensive and noisy, so it runs only when FetchMarketData flagged
        // an anomaly (z-score on return/volume or a 52-week break, spec section 6). With no anomaly
        // the item skips straight to its terminal, the main cost control on the write path.
        // payloadResponseOnly on FetchMarketData makes its output the Choice input, so $.anomaly is
        // the boolean set by the ingestion Lambda. Both branches are terminal for the map item.
        Pass insightSkipped = Pass.Builder.create(this, "InsightSkipped")
                .comment("No anomaly: Bedrock skipped to control cost")
                .build();
        Choice anomalyGate = Choice.Builder.create(this, "AnomalyGate")
                .comment("Generate an insight only on a flagged anomaly")
                .build();
        anomalyGate.when(Condition.booleanEquals("$.anomaly", true), generateInsight);
        anomalyGate.otherwise(insightSkipped);

        Chain perTicker = Chain.start(fetchMarketData).next(anomalyGate);

        // == State 1: read the distinct ticker union (WATCHSET, spec sections 4-5) ==
        CallAwsService readWatchset = CallAwsService.Builder.create(this, "ReadWatchset")
                .service("dynamodb")
                .action("query")
                .parameters(Map.of(
                        "TableName", data.getPlatformTable().getTableName(),
                        "KeyConditionExpression", "PK = :pk",
                        "ExpressionAttributeValues", Map.of(":pk", Map.of("S", "WATCHSET"))))
                .iamResources(List.of(data.getPlatformTable().getTableArn()))
                .resultPath("$.watchset")
                .build();
        readWatchset.addCatch(sendToDlq, catchToDlq);

        // == State 2: Distributed Map fan-out over the tickers ==
        // Bounded concurrency respects provider rate limits (Alpha Vantage free tier ~5 req/min).
        // Per-ticker failures are tolerated so one bad ticker never fails the whole run; each failure
        // still lands in the DLQ via the item catch above. Chosen over a single-Lambda loop (poor
        // isolation, timeout-bound) for per-item retry and a clean DLQ story (spec section 5).
        DistributedMap fanOut = DistributedMap.Builder.create(this, "FanOutTickers")
                .comment("Fan out the per-ticker pipeline across the WATCHSET union")
                .itemsPath("$.watchset.Items")
                .maxConcurrency(5)
                .itemSelector(Map.of(
                        "ticker.$",
                        "$$.Map.Item.Value.ticker.S",
                        "requestedAt.$",
                        "$$.Execution.StartTime",
                        "correlationId.$",
                        "States.Format('{}#{}', $$.Execution.Name, $$.Map.Item.Value.ticker.S)"))
                .toleratedFailurePercentage(100)
                .build();
        // executionType on ProcessorConfig is the working path in this CDK version; the synth-time
        // "use mapExecutionType instead" advisory is benign (mapExecutionType alone fails Map validation).
        fanOut.itemProcessor(
                perTicker,
                ProcessorConfig.builder()
                        .mode(ProcessorMode.DISTRIBUTED)
                        .executionType(ProcessorType.STANDARD)
                        .build());
        fanOut.addCatch(sendToDlq, catchToDlq);

        // On-demand (spec section 5): POST /ingest/{ticker} starts this machine with a ticker in the
        // input. A top-level Choice skips ReadWatchset and wraps the single ticker as the Map's one
        // item, so on-demand runs the identical Fetch -> AnomalyGate -> Insight chain as scheduled.
        Pass singleTickerItem = Pass.Builder.create(this, "SingleTickerItem")
                .comment("On-demand: wrap the requested ticker as a one-item list for the Map")
                .parameters(Map.of("Items", List.of(Map.of("ticker", Map.of("S.$", "$.ticker")))))
                .resultPath("$.watchset")
                .build();

        Choice triggerType = Choice.Builder.create(this, "TriggerType")
                .comment("On-demand (ticker present) skips ReadWatchset; scheduled reads the WATCHSET union")
                .build();
        triggerType.when(Condition.isPresent("$.ticker"), singleTickerItem);
        triggerType.otherwise(readWatchset);

        // Both branches converge on the existing Distributed Map, then succeed.
        singleTickerItem.next(fanOut);
        readWatchset.next(fanOut);
        fanOut.next(executionSucceeded);

        Chain pipelineChain = Chain.start(triggerType);

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
        this.stateMachine = stateMachine;

        // == Symptom alarms (P2/TICKET -> warning topic) ==
        // Ingestion is async/batch, so these page no one in real time; they open a ticket to act today.
        // ExecutionsFailed and DLQ depth are proxies for the true symptom (stale/missing market data)
        // until a data-freshness metric exists.
        this.pipelineFailedAlarm = Alarm.Builder.create(this, "IngestionPipelineFailedAlarm")
                .alarmName("financial-ingestion-pipeline-failed-" + env)
                .alarmDescription("[P2] Ingestion pipeline execution failed - market data may be stale.\n"
                        + "Symptom: Step Functions ExecutionsFailed >= 1 in 5 min.\n"
                        + "Likely causes: source fetch error, Lambda failure, DynamoDB write error.\n"
                        + "First action: open the State Machine execution history and the dashboard Ingestion row.")
                .metric(stateMachine.metricFailed(MetricOptions.builder()
                        .statistic("Sum")
                        .period(Duration.minutes(5))
                        .build()))
                .threshold(1)
                .evaluationPeriods(1)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        this.pipelineFailedAlarm.addAlarmAction(new SnsAction(data.getAlertTopic()));

        this.dlqDepthAlarm = Alarm.Builder.create(this, "IngestionDlqDepthAlarm")
                .alarmName("financial-ingestion-dlq-depth-" + env)
                .alarmDescription("[P2] Ingestion DLQ has messages - one or more tickers failed and need replay.\n"
                        + "Symptom: ApproximateNumberOfMessagesVisible > 0 in 5 min.\n"
                        + "First action: inspect the DLQ messages and the dashboard Ingestion row, then replay.")
                .metric(dlq.metricApproximateNumberOfMessagesVisible(MetricOptions.builder()
                        .statistic("Maximum")
                        .period(Duration.minutes(5))
                        .build()))
                .threshold(0)
                .evaluationPeriods(1)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        this.dlqDepthAlarm.addAlarmAction(new SnsAction(data.getAlertTopic()));

        // ReadWatchset queries the KMS-encrypted table directly, so the state-machine role needs
        // decrypt on the platform table's key in addition to the dynamodb:Query that CallAwsService
        // grants via iamResources.
        data.getEncryptionKey().grantDecrypt(stateMachine);

        // == WATCHSET seed ==
        // The distinct-ticker union is normally maintained by watchlist writes, but no watchlist API
        // exists yet, so seed it at deploy time. The physical id embeds the ticker set, so changing
        // the list re-seeds. Remove this once the watchlist write path maintains WATCHSET.
        List<String> seedTickers = List.of("RELIANCE.NS", "TCS.NS", "INFY.NS", "HDFCBANK.NS", "^NSEI");
        List<Object> seedPutRequests = seedTickers.stream()
                .map(t -> (Object) Map.of(
                        "PutRequest",
                        Map.of(
                                "Item",
                                Map.of(
                                        "PK", Map.of("S", "WATCHSET"),
                                        "SK", Map.of("S", "TICKER#" + t),
                                        "ticker", Map.of("S", t)))))
                .toList();
        AwsCustomResource.Builder.create(this, "WatchsetSeed")
                .onUpdate(AwsSdkCall.builder()
                        .service("DynamoDB")
                        .action("batchWriteItem")
                        .parameters(Map.of(
                                "RequestItems", Map.of(data.getPlatformTable().getTableName(), seedPutRequests)))
                        .physicalResourceId(PhysicalResourceId.of("watchset-seed-" + String.join("-", seedTickers)))
                        .build())
                .policy(AwsCustomResourcePolicy.fromStatements(List.of(
                        PolicyStatement.Builder.create()
                                .actions(List.of("dynamodb:BatchWriteItem"))
                                .resources(List.of(data.getPlatformTable().getTableArn()))
                                .build(),
                        PolicyStatement.Builder.create()
                                .actions(List.of("kms:GenerateDataKey", "kms:Decrypt"))
                                .resources(List.of(data.getEncryptionKey().getKeyArn()))
                                .build())))
                .build();

        // == EventBridge Rule ==
        // prod: every minute during NSE market hours (9:15-15:30 IST, the 3-10 UTC window).
        // dev: every 5 minutes.
        Rule.Builder.create(this, "MarketDataSchedule")
                .ruleName("financial-market-data-schedule-" + env)
                .description("Triggers the financial data pipeline (prod: NSE market hours; dev: every 5 min)")
                .schedule(Schedule.expression(env.equals("prod") ? "cron(*/1 3-10 ? * MON-FRI *)" : "rate(5 minutes)"))
                .targets(List.of(SfnStateMachine.Builder.create(stateMachine)
                        // The pipeline reads tickers from WATCHSET, so the trigger carries no ticker.
                        .input(RuleTargetInput.fromObject(Map.of("source", "eventbridge-schedule")))
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

    /** The ingestion pipeline state machine, exposed so the API stack can grant StartExecution. */
    public StateMachine getStateMachine() {
        return stateMachine;
    }

    /** The ingestion DLQ, exposed so the dashboard stack can wire depth widgets. */
    public Queue getDlq() {
        return dlq;
    }

    /** The pipeline-failed alarm, exposed so the dashboard can list it in the AlarmStatusWidget. */
    public Alarm getPipelineFailedAlarm() {
        return pipelineFailedAlarm;
    }

    /** The DLQ depth alarm, exposed so the dashboard can list it in the AlarmStatusWidget. */
    public Alarm getDlqDepthAlarm() {
        return dlqDepthAlarm;
    }
}
