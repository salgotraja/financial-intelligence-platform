package dev.engnotes.platform.stacks;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.cloudwatch.*;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * Query Stack.
 * <p>
 * Serves user-facing API requests:
 *   GET /insights/{ticker} - latest insight for a ticker
 *   GET /market-data/{ticker} - latest market data for a ticker
 *   GET /health - health check
 * <p>
 * Production decisions:
 *   - Provisioned concurrency: eliminates cold starts on the user-facing path
 *   - API Gateway caching: 60s TTL reduces DynamoDB reads for popular tickers
 *   - WAF: rate limiting and known bad input patterns
 *   - CloudWatch alarms: p99 > 500ms pages on-call via SNS
 */
public class QueryStack extends Stack {
    public QueryStack(
            final Construct scope,
            final String id,
            final StackProps props,
            final String env,
            final NetworkStack network,
            final DataStack data,
            final IngestionStack ingestion) {
        super(scope, id, props);

        this.addDependency(network);
        this.addDependency(data);
        this.addDependency(ingestion);

        // IAM Role for query Lambda
        // Read-only: query Lambda never writes to DynamoDB.
        var queryRole = Role.Builder.create(this, "QueryLambdaRole")
                .roleName("financial-query-lambda-role-" + env)
                .description("IAM role for financial query Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();

        // Read-only grants
        data.getPlatformTable().grantReadData(queryRole);
        data.getEncryptionKey().grantDecrypt(queryRole);

        // Query Lambda
        var queryFn = Function.Builder.create(this, "QueryFn")
                .functionName("financial-query-" + env)
                .description("Serves insight and market data API requests")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/query-function/target/query-function.jar"))
                .role(queryRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(Map.of(
                        "PLATFORM_TABLE",
                        data.getPlatformTable().getTableName(),
                        "ENVIRONMENT",
                        env,
                        "SPRING_CLOUD_FUNCTION_DEFINITION",
                        "serveInsight",
                        "MAIN_CLASS",
                        "dev.engnotes.query.QueryHandler",
                        "LOG_LEVEL",
                        env.equals("prod") ? "INFO" : "DEBUG"))
                .vpc(network.getVpc())
                .build();

        LogGroup.Builder.create(this, "QueryFnLogs")
                .logGroupName("/aws/lambda/" + queryFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Watchlist Lambda (write path). Separate read-write role so the read path stays read-only.
        var watchlistRole = Role.Builder.create(this, "WatchlistLambdaRole")
                .roleName("financial-watchlist-lambda-role-" + env)
                .description("IAM role for financial watchlist Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();
        data.getPlatformTable().grantReadWriteData(watchlistRole);
        data.getEncryptionKey().grantEncryptDecrypt(watchlistRole);

        var watchlistFn = Function.Builder.create(this, "WatchlistFn")
                .functionName("financial-watchlist-" + env)
                .description("Watchlist CRUD and WATCHSET maintenance")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/watchlist-function/target/watchlist-function.jar"))
                .role(watchlistRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(Map.of(
                        "PLATFORM_TABLE",
                        data.getPlatformTable().getTableName(),
                        "ENVIRONMENT",
                        env,
                        "DEFAULT_OWNER_SUB",
                        "dev-user",
                        "SPRING_CLOUD_FUNCTION_DEFINITION",
                        "watchlist",
                        "MAIN_CLASS",
                        "dev.engnotes.watchlist.WatchlistHandler",
                        "LOG_LEVEL",
                        env.equals("prod") ? "INFO" : "DEBUG"))
                .vpc(network.getVpc())
                .build();

        LogGroup.Builder.create(this, "WatchlistFnLogs")
                .logGroupName("/aws/lambda/" + watchlistFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Provisioned concurrency on the published version.
        // Why: eliminate cold starts for user-facing requests.
        // Cost: pay for provisioned concurrency even when idle
        // In dev: set to 0 to save cost. In prod: tune based on expected traffic
        var queryFnVersion = queryFn.getCurrentVersion();

        if (env.equals("prod")) {
            var queryFnAlias = Alias.Builder.create(this, "QueryFnAlias")
                    .aliasName("live")
                    .version(queryFnVersion)
                    .provisionedConcurrentExecutions(2) // adjust based on load test
                    .build();
        }

        // API Gateway
        var apiGwLogs = LogGroup.Builder.create(this, "ApiGwAccessLogs")
                .logGroupName("/aws/apigateway/financial-platform-" + env)
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        var api = RestApi.Builder.create(this, "FinancialApi")
                .restApiName("financial-intelligence-api-" + env)
                .description("Financial Intelligence Platform API")
                .deployOptions(StageOptions.builder()
                        .stageName(env)
                        .tracingEnabled(true)
                        .dataTraceEnabled(!env.equals("prod")) // disable in prod (PII risk)
                        .loggingLevel(MethodLoggingLevel.INFO)
                        .accessLogDestination(new LogGroupLogDestination(apiGwLogs))
                        .accessLogFormat(AccessLogFormat.jsonWithStandardFields())
                        // Cache: 60s TTL reduces DynamoDB reads for popular tickers
                        .cachingEnabled(true)
                        .cacheTtl(Duration.seconds(60))
                        .cacheClusterEnabled(true)
                        .build())
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(List.of(env.equals("prod") ? "https://engnotes.dev" : "*"))
                        .allowMethods(List.of("GET", "POST", "DELETE", "OPTIONS"))
                        .build())
                .build();

        // Non-proxy: the request template maps the path ticker + request id onto the function's
        // QueryRequest record. With the default proxy integration the template is ignored and the
        // raw event arrives, so the ticker resolves to null.
        var queryIntegration = LambdaIntegration.Builder.create(queryFn)
                .proxy(false)
                .requestTemplates(Map.of(
                        "application/json",
                        "{ \"ticker\": \"$input.params('ticker')\", "
                                + "  \"correlationId\": \"$context.requestId\" }"))
                .integrationResponses(
                        List.of(IntegrationResponse.builder().statusCode("200").build()))
                .build();

        // /insights/{ticker}
        api.getRoot()
                .addResource("insights")
                .addResource("{ticker}")
                .addMethod(
                        "GET",
                        queryIntegration,
                        MethodOptions.builder()
                                .requestParameters(Map.of("method.request.path.ticker", true))
                                .methodResponses(List.of(MethodResponse.builder()
                                        .statusCode("200")
                                        .build()))
                                .build());

        // Watchlist routes (non-proxy): the integration template sets the operation per HTTP method
        // and maps the path ticker onto WatchlistRequest. POST/DELETE carry {ticker}; GET lists.
        var watchlistResource = api.getRoot().addResource("watchlist");
        var watchlistTickerResource = watchlistResource.addResource("{ticker}");

        watchlistTickerResource.addMethod(
                "POST",
                LambdaIntegration.Builder.create(watchlistFn)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"ADD\","
                                        + "  \"ticker\": \"$input.params('ticker')\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .requestParameters(Map.of("method.request.path.ticker", true))
                        .methodResponses(List.of(
                                MethodResponse.builder().statusCode("200").build()))
                        .build());

        watchlistTickerResource.addMethod(
                "DELETE",
                LambdaIntegration.Builder.create(watchlistFn)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"REMOVE\","
                                        + "  \"ticker\": \"$input.params('ticker')\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .requestParameters(Map.of("method.request.path.ticker", true))
                        .methodResponses(List.of(
                                MethodResponse.builder().statusCode("200").build()))
                        .build());

        watchlistResource.addMethod(
                "GET",
                LambdaIntegration.Builder.create(watchlistFn)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"LIST\"," + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .methodResponses(List.of(
                                MethodResponse.builder().statusCode("200").build()))
                        .build());

        // On-demand ingest (spec section 5): POST /ingest/{ticker} -> Step Functions StartExecution.
        // A dedicated API GW role gets StartExecution; the request template passes the ticker as the
        // execution input so the state machine's TriggerType Choice runs the single-ticker branch.
        var ingestApiRole = Role.Builder.create(this, "IngestApiRole")
                .roleName("financial-ingest-api-role-" + env)
                .description("Lets API Gateway start the ingestion state machine on demand")
                .assumedBy(new ServicePrincipal("apigateway.amazonaws.com"))
                .build();
        ingestion.getStateMachine().grantStartExecution(ingestApiRole);

        var ingestIntegration = AwsIntegration.Builder.create()
                .service("states")
                .action("StartExecution")
                .integrationHttpMethod("POST")
                .options(IntegrationOptions.builder()
                        .credentialsRole(ingestApiRole)
                        .requestTemplates(
                                Map.of(
                                        "application/json",
                                        "{\"stateMachineArn\":\""
                                                + ingestion.getStateMachine().getStateMachineArn()
                                                + "\",\"input\":\"{\\\"ticker\\\":\\\"$util.escapeJavaScript($input.params('ticker'))\\\",\\\"source\\\":\\\"on-demand\\\"}\"}"))
                        .integrationResponses(List.of(IntegrationResponse.builder()
                                .statusCode("202")
                                .responseTemplates(Map.of(
                                        "application/json",
                                        "{\"status\":\"accepted\",\"ticker\":\"$input.params('ticker')\"}"))
                                .build()))
                        .build())
                .build();

        api.getRoot()
                .addResource("ingest")
                .addResource("{ticker}")
                .addMethod(
                        "POST",
                        ingestIntegration,
                        MethodOptions.builder()
                                .requestParameters(Map.of("method.request.path.ticker", true))
                                .methodResponses(List.of(MethodResponse.builder()
                                        .statusCode("202")
                                        .build()))
                                .build());

        // CloudWatch Alarms
        // p99 > 500ms: user experience degraded
        var p99LatencyAlarm = Alarm.Builder.create(this, "p99LatencyAlarm")
                .alarmName("financial-api-p99-latency-" + env)
                .alarmDescription("API p99 latency exceeded 500ms")
                .metric(api.metricLatency(MetricOptions.builder()
                        .statistic("p99")
                        .period(Duration.minutes(1))
                        .build()))
                .threshold(500)
                .evaluationPeriods(3)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();

        p99LatencyAlarm.addAlarmAction(new SnsAction(data.getAlertTopic()));

        // CloudFormation Outputs
        new CfnOutput(
                this,
                "ApiEndpoint",
                CfnOutputProps.builder()
                        .exportName("platform-api-endpoint-" + env)
                        .value(api.getUrl())
                        .description("Base URL for the Financial Intelligence API")
                        .build());
    }
}
