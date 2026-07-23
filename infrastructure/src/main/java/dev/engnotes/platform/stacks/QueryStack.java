package dev.engnotes.platform.stacks;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.cloudwatch.*;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.wafv2.CfnWebACL;
import software.amazon.awscdk.services.wafv2.CfnWebACLAssociation;
import software.constructs.Construct;

/**
 * Query Stack.
 * <p>
 * Serves user-facing API requests:
 *   GET /insights/{ticker} - latest insight for a ticker
 *   GET /insights - watchlist-scoped insight feed (group insights + ungrouped tickers' latest)
 *   GET /market-data/{ticker} - recent market-data points for a ticker
 *   GET /stories/{ticker} - rule-based per-ticker narrative
 *   GET /health - health check
 * <p>
 * Production decisions:
 *   - SnapStart: eliminates Spring Boot cold starts on the user-facing path
 *   - API Gateway caching: 60s TTL reduces DynamoDB reads for popular tickers
 *   - WAF: rate limiting and known bad input patterns
 *   - CloudWatch alarms: p99 > 500ms pages on-call via SNS
 * <p>
 * Provisioned concurrency is not configured on any alias here: AWS rejects it on the same
 * version/alias as SnapStart, and every Lambda in this stack is SnapStart-enabled. It could be
 * reintroduced on a future Lambda that opts out of SnapStart.
 */
public class QueryStack extends Stack {
    public QueryStack(
            final Construct scope,
            final String id,
            final StackProps props,
            final String env,
            final DataStack data,
            final IngestionStack ingestion,
            final SecurityStack security) {
        super(scope, id, props);

        this.addDependency(data);
        this.addDependency(ingestion);
        this.addDependency(security);

        // IAM Role for query Lambda
        // Read-only: query Lambda never writes to DynamoDB.
        var queryRole = Role.Builder.create(this, "QueryLambdaRole")
                .roleName("financial-query-lambda-role-" + env)
                .description("IAM role for financial query Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();

        // Read-only grants
        data.getPlatformTable().grantReadData(queryRole);
        data.getEncryptionKey().grantDecrypt(queryRole);

        // Query Lambda
        var queryFn = Function.Builder.create(this, "QueryFn")
                .functionName("financial-query-" + env)
                .description("Serves insight API requests")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/query-function/target/query-function.jar"))
                .role(queryRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "serveInsight"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.query.QueryHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "QueryFnLogs")
                .logGroupName("/aws/lambda/" + queryFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Market-data Lambda: second function from the same query-function asset, selected via
        // SPRING_CLOUD_FUNCTION_DEFINITION. Shares the read-only queryRole: this path never writes.
        var marketDataFn = Function.Builder.create(this, "MarketDataFn")
                .functionName("financial-market-data-" + env)
                .description("Serves recent market-data points for charting")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/query-function/target/query-function.jar"))
                .role(queryRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "serveMarketData"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.query.QueryHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "MarketDataFnLogs")
                .logGroupName("/aws/lambda/" + marketDataFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Daily market-data Lambda: serves GET /market-data/{ticker}/daily (spec sub-project B, Task
        // 14), a third function from the same query-function asset. A separate Lambda rather than a
        // branch inside serveMarketData: it returns a distinct response shape (DailyMarketDataResponse,
        // one row per trading day, no intraday series blob), mirroring how insightsFeedFn already gets
        // its own Lambda alongside queryFn/marketDataFn for a related-but-differently-shaped route
        // nested under the same resource tree. Shares the read-only queryRole.
        var dailyMarketDataFn = Function.Builder.create(this, "DailyMarketDataFn")
                .functionName("financial-market-data-daily-" + env)
                .description("Serves daily OHLCV rollups for weekly/period charting")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/query-function/target/query-function.jar"))
                .role(queryRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "serveDailyMarketData"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.query.QueryHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "DailyMarketDataFnLogs")
                .logGroupName("/aws/lambda/" + dailyMarketDataFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Story Lambda: rule-based per-ticker narrative (spec sub-project C, Task 16), a fourth
        // function from the same query-function asset alongside queryFn/marketDataFn/
        // dailyMarketDataFn. Shares the read-only queryRole: StoryQuery only reads the platform
        // table (DAY# rollups, INSIGHT# items, GSI1 group mirrors, TS# points), never writes and
        // never calls Bedrock.
        var storiesFn = Function.Builder.create(this, "StoriesFn")
                .functionName("financial-stories-" + env)
                .description("Serves the rule-based per-ticker story")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/query-function/target/query-function.jar"))
                .role(queryRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "serveStory"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.query.QueryHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "StoriesFnLogs")
                .logGroupName("/aws/lambda/" + storiesFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        var analysisFn = Function.Builder.create(this, "AnalysisFn")
                .functionName("financial-analysis-" + env)
                .description("Serves the deterministic multi-horizon deep analysis")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/query-function/target/query-function.jar"))
                .role(queryRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "serveDeepAnalysis"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.query.QueryHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "AnalysisFnLogs")
                .logGroupName("/aws/lambda/" + analysisFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Insight-feed Lambda: watchlist-scoped insight feed (GET /insights, no ticker). Third
        // function from the same query-function asset, sharing the read-only queryRole: it reads the
        // caller's watchlist plus GSI1 (group-insight mirror items written by insight-function on
        // correlation groups, DataStack's "GSI1 (insight-by-ticker)" comment). GSI1 access needs no
        // extra grant: CDK's Table.grantReadData covers Query on the base table AND every index
        // (tableArn + tableArn/index/*) once the table has an index, which platformTable already does
        // (queryRoleGrantCoversGsi1Index in QueryStackTest pins this).
        var insightsFeedFn = Function.Builder.create(this, "InsightsFeedFn")
                .functionName("financial-insights-feed-" + env)
                .description("Serves the watchlist-scoped insight feed")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/query-function/target/query-function.jar"))
                .role(queryRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "serveInsightFeed"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.query.QueryHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "InsightsFeedFnLogs")
                .logGroupName("/aws/lambda/" + insightsFeedFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Watchlist Lambda (write path). Separate read-write role so the read path stays read-only.
        var watchlistRole = Role.Builder.create(this, "WatchlistLambdaRole")
                .roleName("financial-watchlist-lambda-role-" + env)
                .description("IAM role for financial watchlist Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
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
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("DEFAULT_OWNER_SUB", "dev-user"),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "watchlist"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.watchlist.WatchlistHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "WatchlistFnLogs")
                .logGroupName("/aws/lambda/" + watchlistFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Portfolio Lambda: second function from the same watchlist-function asset, selected via
        // SPRING_CLOUD_FUNCTION_DEFINITION=portfolio. Shares watchlistRole: portfolio holdings live
        // on the same platform table as the watchlist and need the same read-write + KMS grants.
        var portfolioFn = Function.Builder.create(this, "PortfolioFn")
                .functionName("financial-portfolio-" + env)
                .description("Portfolio holdings CRUD (lots-based)")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/watchlist-function/target/watchlist-function.jar"))
                .role(watchlistRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("DEFAULT_OWNER_SUB", "dev-user"),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "portfolio"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.watchlist.WatchlistHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "PortfolioFnLogs")
                .logGroupName("/aws/lambda/" + portfolioFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Consent Lambda (DPDP, spec sub-project B). Read-write on the platform table (consent record
        // + withdrawal purge) and PutItem-only on the audit table (write-only audit trail).
        var consentRole = Role.Builder.create(this, "ConsentLambdaRole")
                .roleName("financial-consent-lambda-role-" + env)
                .description("IAM role for the consent management Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();
        data.getPlatformTable().grantReadWriteData(consentRole);
        data.getAuditTable().grant(consentRole, "dynamodb:PutItem");
        data.getEncryptionKey().grantEncryptDecrypt(consentRole);

        var consentFn = Function.Builder.create(this, "ConsentFn")
                .functionName("financial-consent-" + env)
                .description("Consent management: GET/POST/DELETE /user/consent")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/consent-function/target/consent-function.jar"))
                .role(consentRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("AUDIT_TABLE", data.getAuditTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("CONSENT_POLICY_VERSION", "v1"),
                        Map.entry("DEFAULT_OWNER_SUB", "dev-user"),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "consent"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.consent.ConsentHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "ConsentFnLogs")
                .logGroupName("/aws/lambda/" + consentFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // DSR Lambda (DPDP, spec sub-project C): right-to-access export + right-to-erasure. Read-write
        // on the platform table (export reads; erasure deletes consent + watchlist + WATCHSET mirrors),
        // read + PutItem on the audit table (export reads the trail, both ops append an event), and
        // Cognito ListUsers + AdminDeleteUser to delete the identity. High-privilege grants isolated to
        // this role (kept off the consent role).
        var dsrRole = Role.Builder.create(this, "DsrLambdaRole")
                .roleName("financial-dsr-lambda-role-" + env)
                .description("IAM role for the DPDP data-subject-rights Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();
        data.getPlatformTable().grantReadWriteData(dsrRole);
        data.getAuditTable().grant(dsrRole, "dynamodb:PutItem", "dynamodb:Query");
        data.getEncryptionKey().grantEncryptDecrypt(dsrRole);
        security.getUserPool().grant(dsrRole, "cognito-idp:AdminDeleteUser", "cognito-idp:ListUsers");

        var dsrFn = Function.Builder.create(this, "DsrFn")
                .functionName("financial-dsr-" + env)
                .description("DPDP data-subject rights: GET /user/export, DELETE /user/account")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/dsr-function/target/dsr-function.jar"))
                .role(dsrRole)
                .memorySize(512)
                .timeout(Duration.seconds(30))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("AUDIT_TABLE", data.getAuditTable().getTableName()),
                        Map.entry("USER_POOL_ID", security.getUserPoolId()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "dsr"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.dsr.DsrHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "DsrFnLogs")
                .logGroupName("/aws/lambda/" + dsrFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // API authorizer Lambda: reaches the public Cognito JWKS endpoint natively (ADR 0004, no VPC).
        var authorizerRole = Role.Builder.create(this, "AuthorizerLambdaRole")
                .roleName("financial-authorizer-lambda-role-" + env)
                .description("IAM role for the API token authorizer Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();

        var authorizerFn = Function.Builder.create(this, "AuthorizerFn")
                .functionName("financial-authorizer-" + env)
                .description("API Gateway token authorizer: Cognito JWT + group authorization")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/authorizer-function/target/authorizer-function.jar"))
                .role(authorizerRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("COGNITO_REGION", this.getRegion()),
                        Map.entry("COGNITO_USER_POOL_ID", security.getUserPoolId()),
                        Map.entry("COGNITO_CLIENT_ID", security.getUserPoolClientId()),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "authorize"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.authorizer.AuthorizerHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "AuthorizerFnLogs")
                .logGroupName("/aws/lambda/" + authorizerFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Invoke the authorizer via a published-version alias so SnapStart engages; invoking $LATEST
        // would run the full Spring Boot init on every cold start (added to every authorized request).
        var authorizerFnAlias = Alias.Builder.create(this, "AuthorizerFnAlias")
                .aliasName("live")
                .version(authorizerFn.getCurrentVersion())
                .build();

        var apiAuthorizer = TokenAuthorizer.Builder.create(this, "ApiAuthorizer")
                .authorizerName("financial-cognito-authorizer-" + env)
                .handler(authorizerFnAlias)
                .identitySource("method.request.header.Authorization")
                .resultsCacheTtl(Duration.minutes(5))
                .build();

        // Invoke the query Lambda via a published-version alias so SnapStart engages; invoking
        // $LATEST would run the full Spring Boot init (~5-10s) on every cold start. No provisioned
        // concurrency: AWS rejects it on a SnapStart-enabled version/alias.
        var queryFnAlias = Alias.Builder.create(this, "QueryFnAlias")
                .aliasName("live")
                .version(queryFn.getCurrentVersion())
                .build();

        // Same SnapStart rule for the remaining API Lambdas: only a published version restores from
        // the snapshot, so every integration below targets a live alias, never $LATEST.
        var watchlistFnAlias = Alias.Builder.create(this, "WatchlistFnAlias")
                .aliasName("live")
                .version(watchlistFn.getCurrentVersion())
                .build();
        var portfolioFnAlias = Alias.Builder.create(this, "PortfolioFnAlias")
                .aliasName("live")
                .version(portfolioFn.getCurrentVersion())
                .build();
        var consentFnAlias = Alias.Builder.create(this, "ConsentFnAlias")
                .aliasName("live")
                .version(consentFn.getCurrentVersion())
                .build();
        var dsrFnAlias = Alias.Builder.create(this, "DsrFnAlias")
                .aliasName("live")
                .version(dsrFn.getCurrentVersion())
                .build();

        var marketDataFnAlias = Alias.Builder.create(this, "MarketDataFnAlias")
                .aliasName("live")
                .version(marketDataFn.getCurrentVersion())
                .build();

        var dailyMarketDataFnAlias = Alias.Builder.create(this, "DailyMarketDataFnAlias")
                .aliasName("live")
                .version(dailyMarketDataFn.getCurrentVersion())
                .build();

        var insightsFeedFnAlias = Alias.Builder.create(this, "InsightsFeedFnAlias")
                .aliasName("live")
                .version(insightsFeedFn.getCurrentVersion())
                .build();

        var storiesFnAlias = Alias.Builder.create(this, "StoriesFnAlias")
                .aliasName("live")
                .version(storiesFn.getCurrentVersion())
                .build();

        var analysisFnAlias = Alias.Builder.create(this, "AnalysisFnAlias")
                .aliasName("live")
                .version(analysisFn.getCurrentVersion())
                .build();

        // The dsr Lambda sends the erasure confirmation email inline (workflow collapsed 2026-07-19),
        // so it needs ses:SendEmail scoped to the shared alertEmail identity. A manual statement, not
        // EmailIdentity.grantSendEmail(), since that convenience grant also adds ses:SendRawEmail (the
        // SES v1 raw-MIME action); this Lambda only ever calls SES v2's SendEmail, so ses:SendEmail
        // alone is the least-privilege grant.
        dsrRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("ses:SendEmail"))
                .resources(List.of(data.getSenderIdentity().getEmailIdentityArn()))
                .build());
        dsrFn.addEnvironment("ALERT_EMAIL", data.getAlertEmail());

        // API Gateway
        var apiGwLogs = LogGroup.Builder.create(this, "ApiGwAccessLogs")
                .logGroupName("/aws/apigateway/financial-platform-" + env)
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // Shared by the OPTIONS preflight below and every non-proxy integration/method response:
        // the preflight alone does not cover the actual GET/POST/DELETE response the browser reads.
        String allowOrigin = env.equals("prod") ? "https://engnotes.dev" : "*";

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
                        .allowOrigins(List.of(allowOrigin))
                        .allowMethods(List.of("GET", "POST", "DELETE", "OPTIONS"))
                        .build())
                .build();

        // Authorizer 401/403 and default 4XX/5XX are API Gateway Gateway Responses, not method
        // responses: they short-circuit before any integration runs, so the CORS headers configured
        // above on methods/integrations never apply. Without these, browsers surface a CORS error
        // instead of the real 401/403/5xx status.
        api.addGatewayResponse(
                "Default4xxCors",
                GatewayResponseOptions.builder()
                        .type(ResponseType.DEFAULT_4_XX)
                        .responseHeaders(Map.of("Access-Control-Allow-Origin", "'" + allowOrigin + "'"))
                        .build());
        api.addGatewayResponse(
                "Default5xxCors",
                GatewayResponseOptions.builder()
                        .type(ResponseType.DEFAULT_5_XX)
                        .responseHeaders(Map.of("Access-Control-Allow-Origin", "'" + allowOrigin + "'"))
                        .build());

        // == WAF (spec s12, Task 13) ==
        // Regional Web ACL on the deployed stage, every env (user accepted the cost). Rules run in
        // priority order: the two AWS managed rule groups (OverrideAction none, so each managed
        // rule's own action - almost always Block - applies directly instead of only being counted),
        // then the rate-based rule (300 requests per 5-minute window per source IP, generous for a
        // single-user dev account but a real ceiling). Each rule and the ACL itself carry CloudWatch
        // metrics + sampled requests for visibility.
        var commonRuleSetVisibility = CfnWebACL.VisibilityConfigProperty.builder()
                .sampledRequestsEnabled(true)
                .cloudWatchMetricsEnabled(true)
                .metricName("financial-waf-common-" + env)
                .build();
        var commonRuleSetRule = CfnWebACL.RuleProperty.builder()
                .name("AWSManagedRulesCommonRuleSet")
                .priority(0)
                .overrideAction(CfnWebACL.OverrideActionProperty.builder()
                        .none(Map.of())
                        .build())
                .statement(CfnWebACL.StatementProperty.builder()
                        .managedRuleGroupStatement(CfnWebACL.ManagedRuleGroupStatementProperty.builder()
                                .vendorName("AWS")
                                .name("AWSManagedRulesCommonRuleSet")
                                .build())
                        .build())
                .visibilityConfig(commonRuleSetVisibility)
                .build();

        var knownBadInputsVisibility = CfnWebACL.VisibilityConfigProperty.builder()
                .sampledRequestsEnabled(true)
                .cloudWatchMetricsEnabled(true)
                .metricName("financial-waf-badinputs-" + env)
                .build();
        var knownBadInputsRule = CfnWebACL.RuleProperty.builder()
                .name("AWSManagedRulesKnownBadInputsRuleSet")
                .priority(1)
                .overrideAction(CfnWebACL.OverrideActionProperty.builder()
                        .none(Map.of())
                        .build())
                .statement(CfnWebACL.StatementProperty.builder()
                        .managedRuleGroupStatement(CfnWebACL.ManagedRuleGroupStatementProperty.builder()
                                .vendorName("AWS")
                                .name("AWSManagedRulesKnownBadInputsRuleSet")
                                .build())
                        .build())
                .visibilityConfig(knownBadInputsVisibility)
                .build();

        var rateLimitVisibility = CfnWebACL.VisibilityConfigProperty.builder()
                .sampledRequestsEnabled(true)
                .cloudWatchMetricsEnabled(true)
                .metricName("financial-waf-ratelimit-" + env)
                .build();
        var rateLimitRule = CfnWebACL.RuleProperty.builder()
                .name("RateLimitPerIp")
                .priority(2)
                .action(CfnWebACL.RuleActionProperty.builder()
                        .block(CfnWebACL.BlockActionProperty.builder().build())
                        .build())
                .statement(CfnWebACL.StatementProperty.builder()
                        .rateBasedStatement(CfnWebACL.RateBasedStatementProperty.builder()
                                .limit(300)
                                .evaluationWindowSec(300)
                                .aggregateKeyType("IP")
                                .build())
                        .build())
                .visibilityConfig(rateLimitVisibility)
                .build();

        var webAcl = CfnWebACL.Builder.create(this, "ApiWebAcl")
                .name("financial-waf-" + env)
                .description("Regional Web ACL protecting the Financial Intelligence API")
                .scope("REGIONAL")
                .defaultAction(CfnWebACL.DefaultActionProperty.builder()
                        .allow(CfnWebACL.AllowActionProperty.builder().build())
                        .build())
                .rules(List.of(commonRuleSetRule, knownBadInputsRule, rateLimitRule))
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .sampledRequestsEnabled(true)
                        .cloudWatchMetricsEnabled(true)
                        .metricName("financial-waf-acl-" + env)
                        .build())
                .build();

        // Stage ARN is derived from the RestApi construct (restApiId + deployed stage name), not
        // hardcoded: arn:aws:apigateway:{region}::/restapis/{restApiId}/stages/{stageName}.
        var deployedStage = api.getDeploymentStage();
        String apiStageArn = "arn:aws:apigateway:" + this.getRegion() + "::/restapis/" + api.getRestApiId() + "/stages/"
                + deployedStage.getStageName();

        var webAclAssociation = CfnWebACLAssociation.Builder.create(this, "ApiWebAclAssociation")
                .resourceArn(apiStageArn)
                .webAclArn(webAcl.getAttrArn())
                .build();
        // Explicit dependency: associating before the stage exists fails the deploy.
        webAclAssociation.getNode().addDependency(deployedStage);

        // Non-proxy: the request template maps the path ticker + request id onto the function's
        // QueryRequest record. With the default proxy integration the template is ignored and the
        // raw event arrives, so the ticker resolves to null.
        var queryIntegration = LambdaIntegration.Builder.create(queryFnAlias)
                .proxy(false)
                .requestTemplates(Map.of(
                        "application/json",
                        "{ \"ticker\": \"$util.escapeJavaScript($input.params('ticker'))\", "
                                + "  \"correlationId\": \"$context.requestId\" }"))
                // {ticker} must be a cache key, else the 60s stage cache serves one ticker's
                // response for every ticker (wrong data + collapses the load-test cache mix).
                .cacheKeyParameters(List.of("method.request.path.ticker"))
                .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                .build();

        var insightsResource = api.getRoot().addResource("insights");

        // /insights/{ticker} - protected (readers+)
        insightsResource
                .addResource("{ticker}")
                .addMethod(
                        "GET",
                        queryIntegration,
                        MethodOptions.builder()
                                .authorizer(apiAuthorizer)
                                .authorizationType(AuthorizationType.CUSTOM)
                                .requestParameters(Map.of("method.request.path.ticker", true))
                                .methodResponses(standardMethodResponses())
                                .build());

        // /insights - protected (readers+), watchlist-scoped insight feed (no ticker). Bare resource,
        // separate from /insights/{ticker} above and from RoutePolicy's "insights/*" rule.
        insightsResource.addMethod(
                "GET",
                LambdaIntegration.Builder.create(insightsFeedFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        // Authorization must be a cache key, else the 60s stage cache serves one
                        // caller's feed to every other caller for up to 60s (Task 1's user-scoped
                        // cache-key rule).
                        .cacheKeyParameters(List.of("method.request.header.Authorization"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.header.Authorization", true))
                        .methodResponses(standardMethodResponses())
                        .build());

        var marketDataIntegration = LambdaIntegration.Builder.create(marketDataFnAlias)
                .proxy(false)
                .requestTemplates(Map.of(
                        "application/json",
                        "{ \"ticker\": \"$util.escapeJavaScript($input.params('ticker'))\", "
                                + "  \"correlationId\": \"$context.requestId\" }"))
                .cacheKeyParameters(List.of("method.request.path.ticker"))
                .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                .build();

        // /market-data/{ticker} - protected (readers+), chart data for the frontend
        var marketDataTickerResource = api.getRoot().addResource("market-data").addResource("{ticker}");
        marketDataTickerResource.addMethod(
                "GET",
                marketDataIntegration,
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.path.ticker", true))
                        .methodResponses(standardMethodResponses())
                        .build());

        // /market-data/{ticker}/daily - protected (readers+), daily OHLCV rollups for weekly/period
        // charts (spec sub-project B, Task 14). `days` (default 30, capped 90) is validated and
        // clamped in DailyMarketDataQuery, never in VTL, so it stays optional here; both caller-
        // supplied params are escaped to guard the non-proxy request template against JSON injection.
        var dailyMarketDataIntegration = LambdaIntegration.Builder.create(dailyMarketDataFnAlias)
                .proxy(false)
                .requestTemplates(Map.of(
                        "application/json",
                        "{ \"ticker\": \"$util.escapeJavaScript($input.params('ticker'))\", "
                                + "  \"days\": \"$util.escapeJavaScript($input.params('days'))\", "
                                + "  \"correlationId\": \"$context.requestId\" }"))
                // ticker AND days must both be cache keys, else the 60s stage cache would serve one
                // days-window's response for every other days-window on the same ticker.
                .cacheKeyParameters(List.of("method.request.path.ticker", "method.request.querystring.days"))
                .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                .build();

        marketDataTickerResource
                .addResource("daily")
                .addMethod(
                        "GET",
                        dailyMarketDataIntegration,
                        MethodOptions.builder()
                                .authorizer(apiAuthorizer)
                                .authorizationType(AuthorizationType.CUSTOM)
                                .requestParameters(OrderedMap.of(
                                        Map.entry("method.request.path.ticker", true),
                                        Map.entry("method.request.querystring.days", false)))
                                .methodResponses(standardMethodResponses())
                                .build());

        // /stories/{ticker} - protected (readers+), rule-based per-ticker narrative (spec
        // sub-project C, Task 16). Ticker-only cache key, same as /insights/{ticker} and
        // /market-data/{ticker}: the response is identical for every caller.
        var storiesIntegration = LambdaIntegration.Builder.create(storiesFnAlias)
                .proxy(false)
                .requestTemplates(Map.of(
                        "application/json",
                        "{ \"ticker\": \"$util.escapeJavaScript($input.params('ticker'))\", "
                                + "  \"correlationId\": \"$context.requestId\" }"))
                .cacheKeyParameters(List.of("method.request.path.ticker"))
                .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                .build();

        api.getRoot()
                .addResource("stories")
                .addResource("{ticker}")
                .addMethod(
                        "GET",
                        storiesIntegration,
                        MethodOptions.builder()
                                .authorizer(apiAuthorizer)
                                .authorizationType(AuthorizationType.CUSTOM)
                                .requestParameters(Map.of("method.request.path.ticker", true))
                                .methodResponses(standardMethodResponses())
                                .build());

        // /analysis/{ticker} - protected (readers+), numbers-only deep analysis. Ticker-only
        // cache key: the response is identical for every caller.
        var analysisIntegration = LambdaIntegration.Builder.create(analysisFnAlias)
                .proxy(false)
                .requestTemplates(Map.of(
                        "application/json",
                        "{ \"ticker\": \"$util.escapeJavaScript($input.params('ticker'))\", "
                                + "  \"correlationId\": \"$context.requestId\" }"))
                .cacheKeyParameters(List.of("method.request.path.ticker"))
                .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                .build();

        api.getRoot()
                .addResource("analysis")
                .addResource("{ticker}")
                .addMethod(
                        "GET",
                        analysisIntegration,
                        MethodOptions.builder()
                                .authorizer(apiAuthorizer)
                                .authorizationType(AuthorizationType.CUSTOM)
                                .requestParameters(Map.of("method.request.path.ticker", true))
                                .methodResponses(standardMethodResponses())
                                .build());

        // /health - Lambda-free health check (mock integration, public) for smoke tests and uptime monitors
        api.getRoot()
                .addResource("health")
                .addMethod(
                        "GET",
                        MockIntegration.Builder.create()
                                .requestTemplates(Map.of("application/json", "{\"statusCode\": 200}"))
                                .integrationResponses(List.of(IntegrationResponse.builder()
                                        .statusCode("200")
                                        .responseParameters(Map.of(CORS_HEADER, "'" + allowOrigin + "'"))
                                        .responseTemplates(Map.of("application/json", "{\"status\":\"ok\"}"))
                                        .build()))
                                .build(),
                        MethodOptions.builder()
                                .methodResponses(List.of(MethodResponse.builder()
                                        .statusCode("200")
                                        .responseParameters(Map.of(CORS_HEADER, true))
                                        .build()))
                                .build());

        // Watchlist routes (non-proxy): the integration template sets the operation per HTTP method
        // and maps the path ticker onto WatchlistRequest. POST/DELETE carry {ticker}; GET lists.
        var watchlistResource = api.getRoot().addResource("watchlist");
        var watchlistTickerResource = watchlistResource.addResource("{ticker}");

        watchlistTickerResource.addMethod(
                "POST",
                LambdaIntegration.Builder.create(watchlistFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"ADD\","
                                        + "  \"ticker\": \"$util.escapeJavaScript($input.params('ticker'))\","
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.path.ticker", true))
                        .methodResponses(standardMethodResponses())
                        .build());

        watchlistTickerResource.addMethod(
                "DELETE",
                LambdaIntegration.Builder.create(watchlistFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"REMOVE\","
                                        + "  \"ticker\": \"$util.escapeJavaScript($input.params('ticker'))\","
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(conflictAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.path.ticker", true))
                        .methodResponses(conflictAwareMethodResponses())
                        .build());

        watchlistResource.addMethod(
                "GET",
                LambdaIntegration.Builder.create(watchlistFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"LIST\","
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        // Authorization must be a cache key, else the 60s stage cache serves one
                        // caller's list to every other caller for up to 60s.
                        .cacheKeyParameters(List.of("method.request.header.Authorization"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.header.Authorization", true))
                        .methodResponses(standardMethodResponses())
                        .build());

        // Portfolio routes (non-proxy): POST/DELETE carry {ticker}; POST also forwards the lots array
        // from the body; GET lists. Same jar as watchlist, portfolio bean.
        var portfolioResource = api.getRoot().addResource("portfolio");
        var portfolioTickerResource = portfolioResource.addResource("{ticker}");

        portfolioTickerResource.addMethod(
                "POST",
                LambdaIntegration.Builder.create(portfolioFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"CREATE\","
                                        + "  \"ticker\": \"$util.escapeJavaScript($input.params('ticker'))\","
                                        + "  \"lots\": $input.json('$.lots'),"
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.path.ticker", true))
                        .methodResponses(standardMethodResponses())
                        .build());

        portfolioTickerResource.addMethod(
                "DELETE",
                LambdaIntegration.Builder.create(portfolioFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"DELETE\","
                                        + "  \"ticker\": \"$util.escapeJavaScript($input.params('ticker'))\","
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.path.ticker", true))
                        .methodResponses(standardMethodResponses())
                        .build());

        portfolioResource.addMethod(
                "GET",
                LambdaIntegration.Builder.create(portfolioFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"LIST\","
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .cacheKeyParameters(List.of("method.request.header.Authorization"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.header.Authorization", true))
                        .methodResponses(standardMethodResponses())
                        .build());

        // Portfolio time-machine route: GET /portfolio/history returns the value-curve (core curve
        // only, no benchmark overlay). Same non-proxy portfolio Lambda, HISTORY operation.
        var portfolioHistoryResource = portfolioResource.addResource("history");
        portfolioHistoryResource.addMethod(
                "GET",
                LambdaIntegration.Builder.create(portfolioFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"HISTORY\","
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .cacheKeyParameters(List.of("method.request.header.Authorization"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.header.Authorization", true))
                        .methodResponses(standardMethodResponses())
                        .build());

        // Consent routes (non-proxy, spec sub-project B): POST -> GRANT, GET -> VIEW, DELETE ->
        // WITHDRAW. The caller sub is taken from the authorizer context, never the body. POST reads
        // {version?, purpose} from the body; both fields are escaped via $util.escapeJavaScript to
        // prevent injection. An absent/blank version is defaulted to CONSENT_POLICY_VERSION in the
        // handler; the PreAuthentication trigger (SecurityStack) gates login on the same version.
        var userResource = api.getRoot().addResource("user");
        var consentResource = userResource.addResource("consent");

        consentResource.addMethod(
                "POST",
                LambdaIntegration.Builder.create(consentFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"GRANT\","
                                        + "  \"sub\": \"$context.authorizer.sub\","
                                        + "  \"version\": \"$util.escapeJavaScript($input.path('$.version'))\","
                                        + "  \"purpose\": \"$util.escapeJavaScript($input.path('$.purpose'))\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .methodResponses(standardMethodResponses())
                        .build());

        consentResource.addMethod(
                "GET",
                LambdaIntegration.Builder.create(consentFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"VIEW\","
                                        + "  \"sub\": \"$context.authorizer.sub\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        // Authorization must be a cache key, else the 60s stage cache serves one
                        // caller's consent record to every other caller for up to 60s.
                        .cacheKeyParameters(List.of("method.request.header.Authorization"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.header.Authorization", true))
                        .methodResponses(standardMethodResponses())
                        .build());

        consentResource.addMethod(
                "DELETE",
                LambdaIntegration.Builder.create(consentFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"WITHDRAW\","
                                        + "  \"sub\": \"$context.authorizer.sub\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .methodResponses(standardMethodResponses())
                        .build());

        // DSR routes (non-proxy, spec sub-project C): GET /user/export, DELETE /user/account. The caller
        // sub + comma-joined groups come from the authorizer context; the optional subjectSub query param
        // (admin-on-behalf) is escaped via $util.escapeJavaScript. Server-trusted fields are placed last.
        var exportResource = userResource.addResource("export");
        exportResource.addMethod(
                "GET",
                LambdaIntegration.Builder.create(dsrFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"EXPORT\","
                                        + "  \"subjectSub\": \"$util.escapeJavaScript($input.params('subjectSub'))\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\","
                                        + "  \"callerSub\": \"$context.authorizer.sub\","
                                        + "  \"callerGroups\": \"$context.authorizer.groups\" }"))
                        // Authorization must be a cache key, else the 60s stage cache serves one
                        // caller's export to every other caller for up to 60s. subjectSub too:
                        // the same admin token exporting subject A then subject B within the TTL
                        // would otherwise get A's cached response for B.
                        .cacheKeyParameters(
                                List.of("method.request.header.Authorization", "method.request.querystring.subjectSub"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(OrderedMap.of(
                                Map.entry("method.request.querystring.subjectSub", false),
                                Map.entry("method.request.header.Authorization", true)))
                        .methodResponses(standardMethodResponses())
                        .build());

        // Runs the erasure cascade synchronously in the dsr Lambda (workflow collapsed 2026-07-19);
        // success is 200 with the completed ErasureResult.
        var accountResource = userResource.addResource("account");
        accountResource.addMethod(
                "DELETE",
                LambdaIntegration.Builder.create(dsrFnAlias)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"ERASE\","
                                        + "  \"subjectSub\": \"$util.escapeJavaScript($input.params('subjectSub'))\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\","
                                        + "  \"callerSub\": \"$context.authorizer.sub\","
                                        + "  \"callerGroups\": \"$context.authorizer.groups\" }"))
                        .integrationResponses(errorAwareIntegrationResponses(allowOrigin))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.querystring.subjectSub", false))
                        .methodResponses(standardMethodResponses())
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
                                .responseParameters(Map.of(CORS_HEADER, "'" + allowOrigin + "'"))
                                .responseTemplates(
                                        Map.of(
                                                "application/json",
                                                "{\"status\":\"accepted\",\"ticker\":\"$util.escapeJavaScript($input.params('ticker'))\"}"))
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
                                .authorizer(apiAuthorizer)
                                .authorizationType(AuthorizationType.CUSTOM)
                                .requestParameters(Map.of("method.request.path.ticker", true))
                                .methodResponses(List.of(MethodResponse.builder()
                                        .statusCode("202")
                                        .responseParameters(Map.of(CORS_HEADER, true))
                                        .build()))
                                .build());

        // CloudWatch Alarms (P1 / page -> critical topic)
        // p99 latency: users are experiencing slowness. Sustained 5 min to cut flapping.
        var p99LatencyAlarm = Alarm.Builder.create(this, "p99LatencyAlarm")
                .alarmName("financial-api-p99-latency-" + env)
                .alarmDescription("[P1] API latency - users are experiencing slowness.\n"
                        + "Symptom: p99 latency > 500ms for 5 consecutive minutes.\n"
                        + "Likely causes: cold starts, DynamoDB latency, downstream slowness.\n"
                        + "First action: check the API and Lambdas rows on the dashboard.")
                .metric(api.metricLatency(MetricOptions.builder()
                        .statistic("p99")
                        .period(Duration.minutes(1))
                        .build()))
                .threshold(500)
                .evaluationPeriods(5)
                .datapointsToAlarm(5)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        p99LatencyAlarm.addAlarmAction(new SnsAction(data.getCriticalTopic()));

        // API 5XX error rate: users are receiving server errors. The metric-math encodes a low-volume
        // floor so a single error on an idle system never pages: when 5XX count < 5 the expression
        // yields 0 (never breaches); otherwise it yields the true error-rate percentage.
        var serverErrorRate = MathExpression.Builder.create()
                .expression("IF(errors >= 5, 100 * errors / total, 0)")
                .usingMetrics(OrderedMap.of(
                        Map.entry(
                                "errors",
                                api.metricServerError(MetricOptions.builder()
                                        .statistic("Sum")
                                        .period(Duration.minutes(5))
                                        .build())),
                        Map.entry(
                                "total",
                                api.metricCount(MetricOptions.builder()
                                        .statistic("Sum")
                                        .period(Duration.minutes(5))
                                        .build()))))
                .period(Duration.minutes(5))
                .build();

        var api5xxRateAlarm = Alarm.Builder.create(this, "Api5xxRateAlarm")
                .alarmName("financial-api-5xx-rate-" + env)
                .alarmDescription("[P1] API availability - users are receiving 5XX errors.\n"
                        + "Symptom: 5XX rate > 1% (>= 5 errors) for 5 min.\n"
                        + "Likely causes: Lambda errors, DynamoDB throttle, downstream timeout.\n"
                        + "First action: check the Lambdas row on the dashboard for the failing function.")
                .metric(serverErrorRate)
                .threshold(1)
                .evaluationPeriods(1)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
        api5xxRateAlarm.addAlarmAction(new SnsAction(data.getCriticalTopic()));

        // == Platform dashboard ==
        // One pane aggregating user-facing symptoms (API, Lambda) and diagnostic causes (ingestion,
        // data). Built here in the DAG-sink stack so every widget uses real construct refs.
        var dashboard = Dashboard.Builder.create(this, "PlatformDashboard")
                .dashboardName("financial-platform-" + env)
                .build();

        var fns = OrderedMap.of(
                Map.entry("query", queryFn),
                Map.entry("marketdata", marketDataFn),
                Map.entry("marketdatadaily", dailyMarketDataFn),
                Map.entry("insightsfeed", insightsFeedFn),
                Map.entry("stories", storiesFn),
                Map.entry("analysis", analysisFn),
                Map.entry("watchlist", watchlistFn),
                Map.entry("consent", consentFn),
                Map.entry("dsr", dsrFn),
                Map.entry("authorizer", authorizerFn));

        dashboard.addWidgets(
                GraphWidget.Builder.create()
                        .title("API latency p50/p90/p99")
                        .left(List.of(
                                api.metricLatency(
                                        MetricOptions.builder().statistic("p50").build()),
                                api.metricLatency(
                                        MetricOptions.builder().statistic("p90").build()),
                                api.metricLatency(
                                        MetricOptions.builder().statistic("p99").build())))
                        .width(12)
                        .build(),
                GraphWidget.Builder.create()
                        .title("API requests / 4XX / 5XX")
                        .left(List.of(api.metricCount(), api.metricClientError(), api.metricServerError()))
                        .width(12)
                        .build());

        dashboard.addWidgets(
                GraphWidget.Builder.create()
                        .title("Lambda errors")
                        .left(fns.values().stream().map(fn -> fn.metricErrors()).toList())
                        .width(8)
                        .build(),
                GraphWidget.Builder.create()
                        .title("Lambda throttles")
                        .left(fns.values().stream()
                                .map(fn -> fn.metricThrottles())
                                .toList())
                        .width(8)
                        .build(),
                GraphWidget.Builder.create()
                        .title("Lambda duration p99")
                        .left(fns.values().stream()
                                .map(fn -> fn.metricDuration(
                                        MetricOptions.builder().statistic("p99").build()))
                                .toList())
                        .width(8)
                        .build());

        // metricConcurrentExecutions() is not on IFunction in CDK 2.260.0; use raw Metric per function.
        dashboard.addWidgets(
                GraphWidget.Builder.create()
                        .title("Lambda invocations")
                        .left(fns.values().stream()
                                .map(fn -> fn.metricInvocations())
                                .toList())
                        .width(12)
                        .build(),
                GraphWidget.Builder.create()
                        .title("Lambda concurrent executions")
                        .left(fns.values().stream()
                                .map(fn -> Metric.Builder.create()
                                        .namespace("AWS/Lambda")
                                        .metricName("ConcurrentExecutions")
                                        .dimensionsMap(Map.of("FunctionName", fn.getFunctionName()))
                                        .statistic("Maximum")
                                        .period(Duration.minutes(5))
                                        .build())
                                .toList())
                        .width(12)
                        .build());

        dashboard.addWidgets(
                GraphWidget.Builder.create()
                        .title("Ingestion executions")
                        .left(List.of(
                                ingestion.getStateMachine().metricStarted(),
                                ingestion.getStateMachine().metricSucceeded(),
                                ingestion.getStateMachine().metricFailed(),
                                ingestion.getStateMachine().metricTimedOut()))
                        .width(12)
                        .build(),
                GraphWidget.Builder.create()
                        .title("Ingestion DLQ depth / age")
                        .left(List.of(ingestion.getDlq().metricApproximateNumberOfMessagesVisible()))
                        .right(List.of(ingestion.getDlq().metricApproximateAgeOfOldestMessage()))
                        .width(12)
                        .build());

        dashboard.addWidgets(
                GraphWidget.Builder.create()
                        .title("DynamoDB consumed capacity (platform table)")
                        .left(List.of(
                                data.getPlatformTable().metricConsumedReadCapacityUnits(),
                                data.getPlatformTable().metricConsumedWriteCapacityUnits()))
                        .width(12)
                        .build(),
                GraphWidget.Builder.create()
                        .title("DynamoDB throttled requests (platform table)")
                        .left(List.of(Metric.Builder.create()
                                .namespace("AWS/DynamoDB")
                                .metricName("ThrottledRequests")
                                .dimensionsMap(Map.of(
                                        "TableName", data.getPlatformTable().getTableName()))
                                .statistic("Sum")
                                .period(Duration.minutes(5))
                                .build()))
                        .width(12)
                        .build());

        dashboard.addWidgets(
                GraphWidget.Builder.create()
                        .title("DynamoDB consumed capacity (audit table)")
                        .left(List.of(
                                data.getAuditTable().metricConsumedReadCapacityUnits(),
                                data.getAuditTable().metricConsumedWriteCapacityUnits()))
                        .width(12)
                        .build(),
                GraphWidget.Builder.create()
                        .title("DynamoDB system errors (platform + audit tables)")
                        .left(List.of(
                                Metric.Builder.create()
                                        .namespace("AWS/DynamoDB")
                                        .metricName("SystemErrors")
                                        .dimensionsMap(Map.of(
                                                "TableName",
                                                data.getPlatformTable().getTableName()))
                                        .statistic("Sum")
                                        .period(Duration.minutes(5))
                                        .build(),
                                Metric.Builder.create()
                                        .namespace("AWS/DynamoDB")
                                        .metricName("SystemErrors")
                                        .dimensionsMap(Map.of(
                                                "TableName",
                                                data.getAuditTable().getTableName()))
                                        .statistic("Sum")
                                        .period(Duration.minutes(5))
                                        .build()))
                        .width(12)
                        .build());

        dashboard.addWidgets(AlarmStatusWidget.Builder.create()
                .title("Alarms (P1 + P2)")
                .alarms(List.of(
                        p99LatencyAlarm,
                        api5xxRateAlarm,
                        ingestion.getPipelineFailedAlarm(),
                        ingestion.getDlqDepthAlarm()))
                .width(24)
                .build());

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

    // Non-proxy integrations map Lambda errors via selectionPattern; without these any handler
    // exception surfaces as an opaque 502. Client-input failures map to 400 by message; the 500
    // pattern excludes them because API Gateway's pattern match order is undefined. The 500
    // pattern requires at least one character (+, not *): API Gateway evaluates patterns against
    // an EMPTY errorMessage on successful invocations, so a pattern matching "" hijacks every 200.
    //
    // No shared Java constant backs this string: each phrase below is duplicated in a Lambda
    // exception message in a separate Maven module, and those modules cannot depend on
    // infrastructure/. Changing a phrase here or in any of the sites below without updating the
    // other silently reclassifies that error as a 500. Sites, by phrase:
    //   "Invalid ticker"        - query-function Tickers.java
    //   "allowlist validation"  - watchlist-function TickerValidator.java
    //   "consent required"      - watchlist-function WatchlistHandler.java
    //   "deletion pending"      - watchlist-function WatchlistHandler.java, consent-function
    //                             ConsentStoreService.java
    //   "holding exists"        - watchlist-function WatchlistHandler.java (409 conflict)
    //
    // The watchlist DELETE method uses the conflict-aware responses below (409), not the shared
    // ones, so a held ticker's REMOVE refusal maps to 409 rather than 400.
    private static final String CLIENT_ERROR_PATTERN =
            "Invalid ticker|allowlist validation|consent required|deletion pending";
    private static final String CONFLICT_PATTERN = "holding exists";
    private static final String CORS_HEADER = "method.response.header.Access-Control-Allow-Origin";

    // Non-proxy integrations never emit response headers unless every IntegrationResponse maps them
    // explicitly; the stage-level defaultCorsPreflightOptions only covers the OPTIONS preflight, not
    // the real GET/POST/DELETE response the browser actually reads for the CORS check.
    private static List<IntegrationResponse> errorAwareIntegrationResponses(String allowOrigin) {
        return List.of(
                IntegrationResponse.builder()
                        .statusCode("200")
                        .responseParameters(Map.of(CORS_HEADER, "'" + allowOrigin + "'"))
                        .build(),
                IntegrationResponse.builder()
                        .statusCode("400")
                        .selectionPattern(".*(" + CLIENT_ERROR_PATTERN + ").*")
                        .responseParameters(Map.of(CORS_HEADER, "'" + allowOrigin + "'"))
                        .responseTemplates(Map.of(
                                "application/json",
                                "{\"error\":\"$util.escapeJavaScript($input.path('$.errorMessage'))\"}"))
                        .build(),
                IntegrationResponse.builder()
                        .statusCode("500")
                        .selectionPattern("^((?!" + CLIENT_ERROR_PATTERN + ")(.|\\n))+$")
                        .responseParameters(Map.of(CORS_HEADER, "'" + allowOrigin + "'"))
                        .responseTemplates(Map.of("application/json", "{\"error\":\"internal error\"}"))
                        .build());
    }

    private static List<MethodResponse> standardMethodResponses() {
        return List.of(
                MethodResponse.builder()
                        .statusCode("200")
                        .responseParameters(Map.of(CORS_HEADER, true))
                        .build(),
                MethodResponse.builder()
                        .statusCode("400")
                        .responseParameters(Map.of(CORS_HEADER, true))
                        .build(),
                MethodResponse.builder()
                        .statusCode("500")
                        .responseParameters(Map.of(CORS_HEADER, true))
                        .build());
    }

    // Watchlist DELETE only (audit item E6): a held ticker's REMOVE refusal must map to 409, not
    // 400 or 500. The 500 pattern excludes both CLIENT_ERROR_PATTERN and CONFLICT_PATTERN so
    // neither client-input failures nor holding conflicts get reclassified as server errors.
    private static List<IntegrationResponse> conflictAwareIntegrationResponses(String allowOrigin) {
        return List.of(
                IntegrationResponse.builder()
                        .statusCode("200")
                        .responseParameters(Map.of(CORS_HEADER, "'" + allowOrigin + "'"))
                        .build(),
                IntegrationResponse.builder()
                        .statusCode("400")
                        .selectionPattern(".*(" + CLIENT_ERROR_PATTERN + ").*")
                        .responseParameters(Map.of(CORS_HEADER, "'" + allowOrigin + "'"))
                        .responseTemplates(Map.of(
                                "application/json",
                                "{\"error\":\"$util.escapeJavaScript($input.path('$.errorMessage'))\"}"))
                        .build(),
                IntegrationResponse.builder()
                        .statusCode("409")
                        .selectionPattern(".*(" + CONFLICT_PATTERN + ").*")
                        .responseParameters(Map.of(CORS_HEADER, "'" + allowOrigin + "'"))
                        .responseTemplates(Map.of(
                                "application/json",
                                "{\"error\":\"$util.escapeJavaScript($input.path('$.errorMessage'))\"}"))
                        .build(),
                IntegrationResponse.builder()
                        .statusCode("500")
                        .selectionPattern("^((?!" + CLIENT_ERROR_PATTERN + "|" + CONFLICT_PATTERN + ")(.|\\n))+$")
                        .responseParameters(Map.of(CORS_HEADER, "'" + allowOrigin + "'"))
                        .responseTemplates(Map.of("application/json", "{\"error\":\"internal error\"}"))
                        .build());
    }

    private static List<MethodResponse> conflictAwareMethodResponses() {
        return List.of(
                MethodResponse.builder()
                        .statusCode("200")
                        .responseParameters(Map.of(CORS_HEADER, true))
                        .build(),
                MethodResponse.builder()
                        .statusCode("400")
                        .responseParameters(Map.of(CORS_HEADER, true))
                        .build(),
                MethodResponse.builder()
                        .statusCode("409")
                        .responseParameters(Map.of(CORS_HEADER, true))
                        .build(),
                MethodResponse.builder()
                        .statusCode("500")
                        .responseParameters(Map.of(CORS_HEADER, true))
                        .build());
    }
}
