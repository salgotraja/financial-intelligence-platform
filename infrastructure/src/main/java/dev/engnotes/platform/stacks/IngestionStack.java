package dev.engnotes.platform.stacks;

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
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.events.targets.SfnStateMachine;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.DynamoEventSource;
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
        Map<String, String> commonEnvVars = OrderedMap.of(
                Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                Map.entry("DATA_LAKE_BUCKET", data.getDataLakeBucket().getBucketName()),
                Map.entry("ENVIRONMENT", env),
                Map.entry("POWERTOOLS_SERVICE_NAME", "financial-intelligence-platform"),
                Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG"));

        // == Fetch Market Data Lambda ==
        // SnapStart removes Spring Boot cold start (3-8s -> <200ms) on published versions.
        // FunctionInvoker locates the @SpringBootApplication via MAIN_CLASS (the shaded uber-JAR has
        // no Boot Start-Class manifest entry). Anomaly-gate tunables (spec section 6), set to the
        // AnomalyDetectionService code defaults so they are operable without a redeploy.
        // ANOMALY_MIN_SAMPLES gates the z-score until the baseline has enough history;
        // ANOMALY_Z_THRESHOLD is the standard-deviation trip point.
        Map<String, String> fetchEnv = OrderedMap.of(
                commonEnvVars,
                Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "fetchMarketData"),
                Map.entry("MAIN_CLASS", "dev.engnotes.ingestion.IngestionHandler"),
                Map.entry("MARKET_DATA_API_SECRET", "financial-platform/market-data-api-key"),
                Map.entry("ANOMALY_Z_THRESHOLD", "3.0"),
                Map.entry("ANOMALY_MIN_SAMPLES", "5"));

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
        // Claude is INFERENCE_PROFILE-only in ap-south-1; invoke the global profile id, not the bare
        // foundation-model id (the latter returns ValidationException on demand). Cost tracking +
        // daily-spend circuit breaker (spec section 9): Claude Sonnet list pricing (USD per 1K
        // tokens); the breaker opens once a day's spend reaches the cap and routes to the rule-based
        // fallback. Cost records share the insight table (already granted to the role). Rule-based
        // fallback tunables (spec section 9), set to the RuleBasedInsightGenerator code defaults so
        // the static-threshold signal and its (deliberately lower) confidence are operable without a
        // redeploy. Cross-ticker group insight anti-spam window (Task 7), set to the
        // InsightGenerationService code default so it is operable without a redeploy.
        Map<String, String> insightEnv = OrderedMap.of(
                commonEnvVars,
                Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "generateInsight"),
                Map.entry("MAIN_CLASS", "dev.engnotes.insight.InsightHandler"),
                Map.entry("BEDROCK_MODEL_ID", "global.anthropic.claude-sonnet-4-6"),
                Map.entry("BEDROCK_MAX_TOKENS", "1024"),
                Map.entry("COST_DAILY_CAP_USD", "5.0"),
                Map.entry("BEDROCK_INPUT_PRICE_PER_1K", "0.003"),
                Map.entry("BEDROCK_OUTPUT_PRICE_PER_1K", "0.015"),
                Map.entry("RULE_BULLISH_THRESHOLD_PERCENT", "1.0"),
                Map.entry("RULE_FALLBACK_CONFIDENCE", "0.4"),
                Map.entry("MIN_GROUP_INSIGHT_INTERVAL_MINUTES", "15"));

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

        // == Compute Correlations Lambda (spec section 7) ==
        // A second bean in the insight-function jar (dev.engnotes.insight.InsightHandler), selected by
        // SPRING_CLOUD_FUNCTION_DEFINITION like GenerateInsightFn above. EventBridge invokes it directly
        // (no Step Functions state machine): it reads the WATCHSET, not a single ticker, so it has no
        // place in the per-ticker fan-out chain.
        //
        // Dedicated least-privilege role, NOT the shared ingestionRole: this Lambda only ever reads and
        // writes the platform table (WATCHSET, TS# points, GROUP#/META, TICKER#/GROUP), so it gets none
        // of ingestionRole's Bedrock, Secrets Manager, or S3 grants.
        Role correlationsRole = Role.Builder.create(this, "CorrelationsLambdaRole")
                .roleName("financial-correlations-lambda-role-" + env)
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();
        data.getPlatformTable().grantReadWriteData(correlationsRole);
        data.getEncryptionKey().grantEncryptDecrypt(correlationsRole);

        // Threshold clustering tunable (spec section 7), set to the CorrelationService code default so
        // it is operable without a redeploy.
        Map<String, String> correlationsEnv = OrderedMap.of(
                commonEnvVars,
                Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "computeCorrelations"),
                Map.entry("MAIN_CLASS", "dev.engnotes.insight.InsightHandler"),
                Map.entry("CORRELATION_THRESHOLD", "0.6"));

        Function computeCorrelationsFn = Function.Builder.create(this, "ComputeCorrelationsFn")
                .functionName("financial-correlations-" + env)
                .description("Computes cross-ticker return correlations and persists threshold-clustered groups")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker")
                .code(Code.fromAsset("../functions/insight-function/target/insight-function.jar"))
                .role(correlationsRole)
                .memorySize(512)
                .timeout(Duration.seconds(30))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(correlationsEnv)
                .vpc(network.getVpc())
                .build();

        Alias correlationsAlias = Alias.Builder.create(this, "ComputeCorrelationsAlias")
                .aliasName("live")
                .version(computeCorrelationsFn.getCurrentVersion())
                .build();

        LogGroup.Builder.create(this, "ComputeCorrelationsLogs")
                .logGroupName("/aws/lambda/" + computeCorrelationsFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // 15-minute refresh (spec section 7) inside the same market-hours cron envelope as
        // MarketDataSchedule's main session rule (03:00-09:59 UTC = ~08:30-15:25 IST, Mon-Fri; see that
        // rule's comment for the derivation), stepped by minute 0/15/30/45 instead of every 5. Fixed
        // cadence in every env (unlike ingestion's dev/prod poll-frequency split): the brief's 15-minute
        // refresh isn't an env-tiering knob. Doesn't chase the 15:30/15:35 IST closing-print ticks the
        // way MarketDataCloseSchedule does for ingestion: a correlation group is a rolling-window signal
        // over up to 30 points, not a point-in-time capture, so ending ~20 minutes before the literal
        // close is an acceptable trade rather than a second rule. The bean's own MarketHours guard still
        // no-ops on a holiday, since cron cannot express the NSE holiday calendar.
        Rule.Builder.create(this, "CorrelationsSchedule")
                .ruleName("financial-correlations-schedule-" + env)
                .description("Triggers the correlation pass every 15 minutes during NSE market hours")
                .schedule(Schedule.expression("cron(0/15 3-9 ? * MON-FRI *)"))
                .targets(List.of(LambdaFunction.Builder.create(correlationsAlias)
                        .event(RuleTargetInput.fromObject(Map.of("source", "eventbridge-schedule")))
                        .retryAttempts(2)
                        .build()))
                .build();

        // Watchlist-add history backfill: a year of daily bars written as DAY# items the first
        // time a ticker enters the WATCHSET. Triggered by the platform table's NEW_IMAGE stream,
        // filtered to WATCHSET INSERTs so watch adds (rare) invoke it and market-data volume
        // never does. This is the stream's SECOND consumer (the notifier is the first) — DynamoDB
        // streams support at most two; a third needs a fan-out redesign.
        Map<String, String> backfillEnv = OrderedMap.of(
                commonEnvVars,
                Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "backfillDailyHistory"),
                Map.entry("MAIN_CLASS", "dev.engnotes.ingestion.IngestionHandler"));

        Function historyBackfillFn = Function.Builder.create(this, "HistoryBackfillFn")
                .functionName("financial-history-backfill-" + env)
                .description("Backfills a year of daily rollups when a ticker joins the watchset")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker")
                .code(Code.fromAsset("../functions/ingestion-function/target/ingestion-function.jar"))
                .role(ingestionRole)
                .memorySize(512)
                .timeout(Duration.seconds(60))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(backfillEnv)
                .vpc(network.getVpc())
                .build();

        Alias historyBackfillAlias = Alias.Builder.create(this, "HistoryBackfillAlias")
                .aliasName("live")
                .version(historyBackfillFn.getCurrentVersion())
                .build();

        LogGroup.Builder.create(this, "HistoryBackfillLogs")
                .logGroupName("/aws/lambda/" + historyBackfillFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Small batches (watch adds arrive one at a time) and bounded retries: a poison record
        // must not block the shard. Per-ticker isolation still holds inside a batch (one bad
        // ticker doesn't stop the others from being attempted), but the bean now rethrows after
        // the loop if any ticker failed, so these retries genuinely re-run the batch for
        // transient failures (Yahoo hiccup, DDB throttle) — safe because the backfill is
        // idempotent (conditional puts make a retried already-succeeded ticker a no-op). Once
        // retries are exhausted the record is dropped; the sweep script remains the backstop.
        historyBackfillAlias.addEventSource(DynamoEventSource.Builder.create(data.getPlatformTable())
                .startingPosition(StartingPosition.LATEST)
                .batchSize(5)
                .retryAttempts(2)
                .filters(List.of(FilterCriteria.filter(OrderedMap.of(
                        Map.entry("eventName", FilterRule.isEqual("INSERT")),
                        Map.entry(
                                "dynamodb",
                                Map.of("Keys", Map.of("PK", Map.of("S", FilterRule.isEqual("WATCHSET")))))))))
                .build());

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
                .parameters(OrderedMap.of(
                        Map.entry("TableName", data.getPlatformTable().getTableName()),
                        Map.entry("KeyConditionExpression", "PK = :pk"),
                        Map.entry("ExpressionAttributeValues", Map.of(":pk", Map.of("S", "WATCHSET")))))
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
                // 2, not 5: this account's Lambda concurrency quota is 10 TOTAL, and a fan-out
                // burst at 5 (x fetch+insight per item) starved the API-path authorizers into
                // "Rate Exceeded" 500s (observed live 2026-07-12). Keep pipeline bursts well
                // under half the pool until the requested quota increase (->1000) is granted.
                .maxConcurrency(2)
                .itemSelector(OrderedMap.of(
                        Map.entry("ticker.$", "$$.Map.Item.Value.ticker.S"),
                        Map.entry("requestedAt.$", "$$.Execution.StartTime"),
                        Map.entry(
                                "correlationId.$",
                                "States.Format('{}#{}', $$.Execution.Name, $$.Map.Item.Value.ticker.S)"),
                        Map.entry("source.$", "$$.Execution.Input.source")))
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
        List<String> seedTickers =
                List.of("RELIANCE.NS", "TCS.NS", "INFY.NS", "HDFCBANK.NS", "^NSEI", "^BSESN", "^NSEBANK");
        List<Object> seedPutRequests = seedTickers.stream()
                .map(t -> (Object) Map.of(
                        "PutRequest",
                        Map.of(
                                "Item",
                                OrderedMap.of(
                                        Map.entry("PK", Map.of("S", "WATCHSET")),
                                        Map.entry("SK", Map.of("S", "TICKER#" + t)),
                                        Map.entry("ticker", Map.of("S", t))))))
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

        // == EventBridge Rules ==
        // Poll only during the NSE session (09:00-15:35 IST, Mon-Fri) so we never ingest
        // flat post-close prices overnight or on weekends; manual on-demand ingest is
        // unaffected. A single cron applies its minute pattern to every hour in its range,
        // so the :05-past-the-hour close needs its own rule:
        //   main:  09:00-15:29 IST (03:00-09:59 UTC; ~30m harmless pre-open padding)
        //   close: 15:30 & 15:35 IST (10:00/10:05 UTC), capturing the closing prints.
        // prod polls every minute, dev every 5.
        boolean prod = env.equals("prod");
        Rule.Builder.create(this, "MarketDataSchedule")
                .ruleName("financial-market-data-schedule-" + env)
                .description("Triggers the financial data pipeline during NSE market hours")
                .schedule(Schedule.expression(prod ? "cron(*/1 3-9 ? * MON-FRI *)" : "cron(*/5 3-9 ? * MON-FRI *)"))
                .targets(List.of(SfnStateMachine.Builder.create(stateMachine)
                        // The pipeline reads tickers from WATCHSET, so the trigger carries no ticker.
                        .input(RuleTargetInput.fromObject(Map.of("source", "eventbridge-schedule")))
                        .deadLetterQueue(dlq)
                        .retryAttempts(2)
                        .build()))
                .build();

        Rule.Builder.create(this, "MarketDataCloseSchedule")
                .ruleName("financial-market-data-close-schedule-" + env)
                .description("Captures the NSE closing prints (15:30 & 15:35 IST)")
                .schedule(Schedule.expression(prod ? "cron(0-5 10 ? * MON-FRI *)" : "cron(0,5 10 ? * MON-FRI *)"))
                .targets(List.of(SfnStateMachine.Builder.create(stateMachine)
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
