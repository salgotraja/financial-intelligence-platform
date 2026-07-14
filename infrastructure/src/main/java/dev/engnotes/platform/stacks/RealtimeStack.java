package dev.engnotes.platform.stacks;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.*;
import software.amazon.awscdk.aws_apigatewayv2_authorizers.WebSocketLambdaAuthorizer;
import software.amazon.awscdk.aws_apigatewayv2_authorizers.WebSocketLambdaAuthorizerProps;
import software.amazon.awscdk.aws_apigatewayv2_integrations.WebSocketLambdaIntegration;
import software.amazon.awscdk.services.apigatewayv2.WebSocketApi;
import software.amazon.awscdk.services.apigatewayv2.WebSocketRouteOptions;
import software.amazon.awscdk.services.apigatewayv2.WebSocketStage;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps;
import software.amazon.awscdk.services.dynamodb.ProjectionType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.DynamoEventSource;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * Realtime Stack: pushes newly stored insights to connected browsers.
 *
 * <p>WebSocket API ($connect guarded by a REQUEST Lambda authorizer reading ?token=,
 * $disconnect, subscribe) + a connections table (PK=ticker, SK=connectionId, 2h TTL safety net,
 * by-connection GSI for disconnect cleanup) + a notifier Lambda consuming the platform table's
 * NEW_IMAGE stream (INSERT-filtered) and posting via the Management API, pruning 410-Gone
 * connections.
 *
 * <p>All Lambdas are outside the VPC (like the REST authorizer): they need only public AWS
 * endpoints (DynamoDB, execute-api, Cognito JWKS), which keeps the realtime path independent of
 * the NAT. Ephemeral by design: teardown destroys this stack; the platform table stream stays
 * enabled on the retained Data stack (costless while unconsumed).
 */
public class RealtimeStack extends Stack {
    public RealtimeStack(
            final Construct scope,
            final String id,
            final StackProps props,
            final String env,
            final DataStack data,
            final SecurityStack security) {
        super(scope, id, props);

        this.addDependency(data);
        this.addDependency(security);

        // Connections table. Non-sensitive coordination data (connection ids + tickers), so
        // default AWS-owned encryption and DESTROY in every env: rows are worthless after ~2h.
        var connectionsTable = Table.Builder.create(this, "ConnectionsTable")
                .tableName("financial-connections-" + env)
                .partitionKey(Attribute.builder()
                        .name("ticker")
                        .type(AttributeType.STRING)
                        .build())
                .sortKey(Attribute.builder()
                        .name("connectionId")
                        .type(AttributeType.STRING)
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ttl")
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        connectionsTable.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                .indexName("by-connection")
                .partitionKey(Attribute.builder()
                        .name("connectionId")
                        .type(AttributeType.STRING)
                        .build())
                .projectionType(ProjectionType.KEYS_ONLY)
                .build());

        // WebSocket $connect authorizer (NOT in the VPC: it must reach the public Cognito JWKS
        // endpoint). Same jar as the REST authorizer, second bean.
        var wsAuthorizerRole = Role.Builder.create(this, "WsAuthorizerLambdaRole")
                .roleName("financial-ws-authorizer-lambda-role-" + env)
                .description("IAM role for the WebSocket connect authorizer Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();

        var wsAuthorizerFn = Function.Builder.create(this, "WsAuthorizerFn")
                .functionName("financial-ws-authorizer-" + env)
                .description("WebSocket $connect authorizer: Cognito JWT via ?token= query param")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/authorizer-function/target/authorizer-function.jar"))
                .role(wsAuthorizerRole)
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
                        "authorizeWebSocket",
                        "MAIN_CLASS",
                        "dev.engnotes.authorizer.AuthorizerHandler",
                        "LOG_LEVEL",
                        env.equals("prod") ? "INFO" : "DEBUG"))
                .build();

        LogGroup.Builder.create(this, "WsAuthorizerFnLogs")
                .logGroupName("/aws/lambda/" + wsAuthorizerFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        var wsAuthorizerFnAlias = Alias.Builder.create(this, "WsAuthorizerFnAlias")
                .aliasName("live")
                .version(wsAuthorizerFn.getCurrentVersion())
                .build();

        // Connection-management Lambda ($connect/$disconnect/subscribe routes).
        var manageRole = Role.Builder.create(this, "ManageConnectionLambdaRole")
                .roleName("financial-manage-connection-lambda-role-" + env)
                .description("IAM role for the WebSocket connection-management Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();
        connectionsTable.grantReadWriteData(manageRole);

        var manageFn = Function.Builder.create(this, "ManageConnectionFn")
                .functionName("financial-manage-connection-" + env)
                .description("WebSocket route handling: subscribe registrations and disconnect cleanup")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/notifier-function/target/notifier-function.jar"))
                .role(manageRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(Map.of(
                        "CONNECTIONS_TABLE",
                        connectionsTable.getTableName(),
                        "ENVIRONMENT",
                        env,
                        "SPRING_CLOUD_FUNCTION_DEFINITION",
                        "manageConnection",
                        "MAIN_CLASS",
                        "dev.engnotes.notifier.NotifierHandler",
                        "LOG_LEVEL",
                        env.equals("prod") ? "INFO" : "DEBUG"))
                .build();

        LogGroup.Builder.create(this, "ManageConnectionFnLogs")
                .logGroupName("/aws/lambda/" + manageFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        var manageFnAlias = Alias.Builder.create(this, "ManageConnectionFnAlias")
                .aliasName("live")
                .version(manageFn.getCurrentVersion())
                .build();

        // Notifier Lambda (stream consumer + Management API push).
        var notifierRole = Role.Builder.create(this, "NotifierLambdaRole")
                .roleName("financial-notifier-lambda-role-" + env)
                .description("IAM role for the insight fan-out Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();
        connectionsTable.grantReadWriteData(notifierRole);
        // Stream records of the KMS-encrypted platform table need decrypt (DynamoEventSource
        // grants the stream-read actions; the key grant is ours to add).
        data.getEncryptionKey().grantDecrypt(notifierRole);

        var notifierFn = Function.Builder.create(this, "NotifierFn")
                .functionName("financial-notifier-" + env)
                .description("Pushes INSERTed insights to subscribed WebSocket connections")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/notifier-function/target/notifier-function.jar"))
                .role(notifierRole)
                .memorySize(512)
                .timeout(Duration.seconds(30))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(Map.of(
                        "CONNECTIONS_TABLE",
                        connectionsTable.getTableName(),
                        "ENVIRONMENT",
                        env,
                        "SPRING_CLOUD_FUNCTION_DEFINITION",
                        "notifyInsight",
                        "MAIN_CLASS",
                        "dev.engnotes.notifier.NotifierHandler",
                        "LOG_LEVEL",
                        env.equals("prod") ? "INFO" : "DEBUG"))
                .build();

        LogGroup.Builder.create(this, "NotifierFnLogs")
                .logGroupName("/aws/lambda/" + notifierFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        var notifierFnAlias = Alias.Builder.create(this, "NotifierFnAlias")
                .aliasName("live")
                .version(notifierFn.getCurrentVersion())
                .build();

        // WebSocket API. Route selection is the default $request.body.action, so
        // {"action":"subscribe",...} lands on the subscribe route.
        var webSocketApi = WebSocketApi.Builder.create(this, "RealtimeApi")
                .apiName("financial-realtime-" + env)
                .description("Realtime insight feed")
                .connectRouteOptions(WebSocketRouteOptions.builder()
                        .integration(new WebSocketLambdaIntegration("ConnectIntegration", manageFnAlias))
                        .authorizer(new WebSocketLambdaAuthorizer(
                                "WsConnectAuthorizer",
                                wsAuthorizerFnAlias,
                                WebSocketLambdaAuthorizerProps.builder()
                                        .authorizerName("financial-ws-authorizer-" + env)
                                        .identitySource(List.of("route.request.querystring.token"))
                                        .build()))
                        .build())
                .disconnectRouteOptions(WebSocketRouteOptions.builder()
                        .integration(new WebSocketLambdaIntegration("DisconnectIntegration", manageFnAlias))
                        .build())
                .build();
        webSocketApi.addRoute(
                "subscribe",
                WebSocketRouteOptions.builder()
                        .integration(new WebSocketLambdaIntegration("SubscribeIntegration", manageFnAlias))
                        .build());

        var stage = WebSocketStage.Builder.create(this, "RealtimeStage")
                .webSocketApi(webSocketApi)
                .stageName(env)
                .autoDeploy(true)
                .build();

        // The Management API endpoint is stage-scoped; hand it to the notifier and let it post.
        notifierFn.addEnvironment("WS_CALLBACK_URL", stage.getCallbackUrl());
        stage.grantManagementApiAccess(notifierRole);

        // Fan-out trigger: INSERTs on the platform table whose SK begins with INSIGHT#. The event
        // source filter (not just the code-level SK check in the notifier) stops market-data
        // INSERTs from invoking the Lambda at all, since market-data volume dwarfs insight volume.
        // Bounded retries: a poison batch must not block the shard forever; missed pushes are
        // tolerable (clients re-query on load).
        notifierFnAlias.addEventSource(DynamoEventSource.Builder.create(data.getPlatformTable())
                .startingPosition(StartingPosition.LATEST)
                .batchSize(10)
                .retryAttempts(2)
                .filters(List.of(FilterCriteria.filter(Map.of(
                        "eventName",
                        FilterRule.isEqual("INSERT"),
                        "dynamodb",
                        Map.of("Keys", Map.of("SK", Map.of("S", FilterRule.beginsWith("INSIGHT#"))))))))
                .build());

        new CfnOutput(
                this,
                "WebSocketEndpoint",
                CfnOutputProps.builder()
                        .exportName("platform-websocket-endpoint-" + env)
                        .value(stage.getUrl())
                        .description("wss:// endpoint for the realtime insight feed")
                        .build());
    }
}
