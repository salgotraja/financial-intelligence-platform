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
            final IngestionStack ingestion,
            final SecurityStack security) {
        super(scope, id, props);

        this.addDependency(network);
        this.addDependency(data);
        this.addDependency(ingestion);
        this.addDependency(security);

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

        // Consent Lambda (DPDP, spec sub-project B). Read-write on the platform table (consent record
        // + withdrawal purge) and PutItem-only on the audit table (write-only audit trail).
        var consentRole = Role.Builder.create(this, "ConsentLambdaRole")
                .roleName("financial-consent-lambda-role-" + env)
                .description("IAM role for the consent management Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
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
                .environment(Map.of(
                        "PLATFORM_TABLE",
                        data.getPlatformTable().getTableName(),
                        "AUDIT_TABLE",
                        data.getAuditTable().getTableName(),
                        "ENVIRONMENT",
                        env,
                        "CONSENT_VERSION",
                        "v1",
                        "DEFAULT_OWNER_SUB",
                        "dev-user",
                        "SPRING_CLOUD_FUNCTION_DEFINITION",
                        "consent",
                        "MAIN_CLASS",
                        "dev.engnotes.consent.ConsentHandler",
                        "LOG_LEVEL",
                        env.equals("prod") ? "INFO" : "DEBUG"))
                .vpc(network.getVpc())
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
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaVPCAccessExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
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
                .environment(Map.of(
                        "PLATFORM_TABLE",
                        data.getPlatformTable().getTableName(),
                        "AUDIT_TABLE",
                        data.getAuditTable().getTableName(),
                        "USER_POOL_ID",
                        security.getUserPoolId(),
                        "ENVIRONMENT",
                        env,
                        "SPRING_CLOUD_FUNCTION_DEFINITION",
                        "dsr",
                        "MAIN_CLASS",
                        "dev.engnotes.dsr.DsrHandler",
                        "LOG_LEVEL",
                        env.equals("prod") ? "INFO" : "DEBUG"))
                .vpc(network.getVpc())
                .build();

        LogGroup.Builder.create(this, "DsrFnLogs")
                .logGroupName("/aws/lambda/" + dsrFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // API authorizer Lambda (NOT in the VPC: it must reach the public Cognito JWKS endpoint).
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
                .environment(Map.of(
                        "COGNITO_REGION",
                        this.getRegion(),
                        "COGNITO_USER_POOL_ID",
                        security.getUserPoolId(),
                        "COGNITO_CLIENT_ID",
                        security.getUserPoolClientId(),
                        "SPRING_CLOUD_FUNCTION_DEFINITION",
                        "authorize",
                        "MAIN_CLASS",
                        "dev.engnotes.authorizer.AuthorizerHandler",
                        "LOG_LEVEL",
                        env.equals("prod") ? "INFO" : "DEBUG"))
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
        // $LATEST would run the full Spring Boot init (~5-10s) on every cold start. Provisioned
        // concurrency (paid even when idle) stays prod-only.
        var queryAliasBuilder =
                Alias.Builder.create(this, "QueryFnAlias").aliasName("live").version(queryFn.getCurrentVersion());
        if (env.equals("prod")) {
            queryAliasBuilder.provisionedConcurrentExecutions(2); // adjust based on load test
        }
        var queryFnAlias = queryAliasBuilder.build();

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
        var queryIntegration = LambdaIntegration.Builder.create(queryFnAlias)
                .proxy(false)
                .requestTemplates(Map.of(
                        "application/json",
                        "{ \"ticker\": \"$input.params('ticker')\", "
                                + "  \"correlationId\": \"$context.requestId\" }"))
                // {ticker} must be a cache key, else the 60s stage cache serves one ticker's
                // response for every ticker (wrong data + collapses the load-test cache mix).
                .cacheKeyParameters(List.of("method.request.path.ticker"))
                .integrationResponses(
                        List.of(IntegrationResponse.builder().statusCode("200").build()))
                .build();

        // /insights/{ticker} - protected (readers+)
        api.getRoot()
                .addResource("insights")
                .addResource("{ticker}")
                .addMethod(
                        "GET",
                        queryIntegration,
                        MethodOptions.builder()
                                .authorizer(apiAuthorizer)
                                .authorizationType(AuthorizationType.CUSTOM)
                                .requestParameters(Map.of("method.request.path.ticker", true))
                                .methodResponses(List.of(MethodResponse.builder()
                                        .statusCode("200")
                                        .build()))
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
                                        .responseTemplates(Map.of("application/json", "{\"status\":\"ok\"}"))
                                        .build()))
                                .build(),
                        MethodOptions.builder()
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
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
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
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
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
                                "{ \"operation\": \"LIST\","
                                        + "  \"ownerSub\": \"$context.authorizer.sub\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .methodResponses(List.of(
                                MethodResponse.builder().statusCode("200").build()))
                        .build());

        // Consent routes (non-proxy, spec sub-project B): POST -> GRANT, GET -> VIEW, DELETE ->
        // WITHDRAW. The caller sub is taken from the authorizer context, never the body. POST reads
        // {version?, purpose} from the body; both fields are escaped via $util.escapeJavaScript to
        // prevent injection. An absent/blank version is defaulted to CONSENT_VERSION in the handler.
        var userResource = api.getRoot().addResource("user");
        var consentResource = userResource.addResource("consent");

        consentResource.addMethod(
                "POST",
                LambdaIntegration.Builder.create(consentFn)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"GRANT\","
                                        + "  \"sub\": \"$context.authorizer.sub\","
                                        + "  \"version\": \"$util.escapeJavaScript($input.path('$.version'))\","
                                        + "  \"purpose\": \"$util.escapeJavaScript($input.path('$.purpose'))\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .methodResponses(List.of(
                                MethodResponse.builder().statusCode("200").build()))
                        .build());

        consentResource.addMethod(
                "GET",
                LambdaIntegration.Builder.create(consentFn)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"VIEW\","
                                        + "  \"sub\": \"$context.authorizer.sub\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .methodResponses(List.of(
                                MethodResponse.builder().statusCode("200").build()))
                        .build());

        consentResource.addMethod(
                "DELETE",
                LambdaIntegration.Builder.create(consentFn)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"WITHDRAW\","
                                        + "  \"sub\": \"$context.authorizer.sub\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .methodResponses(List.of(
                                MethodResponse.builder().statusCode("200").build()))
                        .build());

        // DSR routes (non-proxy, spec sub-project C): GET /user/export, DELETE /user/account. The caller
        // sub + comma-joined groups come from the authorizer context; the optional subjectSub query param
        // (admin-on-behalf) is escaped via $util.escapeJavaScript. Server-trusted fields are placed last.
        var exportResource = userResource.addResource("export");
        exportResource.addMethod(
                "GET",
                LambdaIntegration.Builder.create(dsrFn)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"EXPORT\","
                                        + "  \"subjectSub\": \"$util.escapeJavaScript($input.params('subjectSub'))\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\","
                                        + "  \"callerSub\": \"$context.authorizer.sub\","
                                        + "  \"callerGroups\": \"$context.authorizer.groups\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.querystring.subjectSub", false))
                        .methodResponses(List.of(
                                MethodResponse.builder().statusCode("200").build()))
                        .build());

        var accountResource = userResource.addResource("account");
        accountResource.addMethod(
                "DELETE",
                LambdaIntegration.Builder.create(dsrFn)
                        .proxy(false)
                        .requestTemplates(Map.of(
                                "application/json",
                                "{ \"operation\": \"ERASE\","
                                        + "  \"subjectSub\": \"$util.escapeJavaScript($input.params('subjectSub'))\","
                                        + "  \"sourceIp\": \"$context.identity.sourceIp\","
                                        + "  \"correlationId\": \"$context.requestId\","
                                        + "  \"callerSub\": \"$context.authorizer.sub\","
                                        + "  \"callerGroups\": \"$context.authorizer.groups\" }"))
                        .integrationResponses(List.of(
                                IntegrationResponse.builder().statusCode("200").build()))
                        .build(),
                MethodOptions.builder()
                        .authorizer(apiAuthorizer)
                        .authorizationType(AuthorizationType.CUSTOM)
                        .requestParameters(Map.of("method.request.querystring.subjectSub", false))
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
                                .authorizer(apiAuthorizer)
                                .authorizationType(AuthorizationType.CUSTOM)
                                .requestParameters(Map.of("method.request.path.ticker", true))
                                .methodResponses(List.of(MethodResponse.builder()
                                        .statusCode("202")
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
                .usingMetrics(Map.of(
                        "errors",
                        api.metricServerError(MetricOptions.builder()
                                .statistic("Sum")
                                .period(Duration.minutes(5))
                                .build()),
                        "total",
                        api.metricCount(MetricOptions.builder()
                                .statistic("Sum")
                                .period(Duration.minutes(5))
                                .build())))
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

        var fns = Map.of(
                "query", queryFn,
                "watchlist", watchlistFn,
                "consent", consentFn,
                "dsr", dsrFn,
                "authorizer", authorizerFn);

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
}
