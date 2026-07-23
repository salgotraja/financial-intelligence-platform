package dev.engnotes.platform.stacks;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cognito.*;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.SnapStartConf;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * Security Stack - the identity half of the platform (spec sections 3, 11).
 *
 * <p>Holds the Cognito user pool, groups, app client, and Hosted UI domain. The pool is stateful
 * (real users), so this stack is persistent (RETAIN) and must NOT be in the teardown set alongside
 * Network/Ingestion/Query. MFA is env-gated (OPTIONAL in dev, REQUIRED in prod), TOTP only.
 */
public class SecurityStack extends Stack {

    private final UserPool userPool;
    private final UserPoolClient userPoolClient;

    public SecurityStack(
            final Construct scope, final String id, final StackProps props, final String env, final DataStack data) {
        super(scope, id, props);

        this.addDependency(data);

        boolean prod = env.equals("prod");

        this.userPool = UserPool.Builder.create(this, "UserPool")
                .userPoolName("financial-platform-users-" + env)
                .selfSignUpEnabled(true)
                .signInAliases(SignInAliases.builder().email(true).build())
                .autoVerify(AutoVerifiedAttrs.builder().email(true).build())
                .standardAttributes(StandardAttributes.builder()
                        .email(StandardAttribute.builder()
                                .required(true)
                                .mutable(true)
                                .build())
                        .fullname(StandardAttribute.builder()
                                .required(false)
                                .mutable(true)
                                .build())
                        .phoneNumber(StandardAttribute.builder()
                                .required(false)
                                .mutable(true)
                                .build())
                        .build())
                .customAttributes(OrderedMap.of(
                        Map.entry(
                                "consent_given",
                                StringAttribute.Builder.create().mutable(true).build()),
                        Map.entry(
                                "consent_timestamp",
                                StringAttribute.Builder.create().mutable(true).build()),
                        Map.entry(
                                "consent_version",
                                StringAttribute.Builder.create().mutable(true).build()),
                        Map.entry(
                                "processing_purpose",
                                StringAttribute.Builder.create().mutable(true).build())))
                .passwordPolicy(PasswordPolicy.builder()
                        .minLength(12)
                        .requireLowercase(true)
                        .requireUppercase(true)
                        .requireDigits(true)
                        .requireSymbols(true)
                        .build())
                .mfa(prod ? Mfa.REQUIRED : Mfa.OPTIONAL)
                .mfaSecondFactor(MfaSecondFactor.builder().otp(true).sms(false).build())
                .accountRecovery(AccountRecovery.EMAIL_ONLY)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        for (String group : List.of("readers", "premium", "admins")) {
            CfnUserPoolGroup.Builder.create(this, "Group-" + group)
                    .userPoolId(userPool.getUserPoolId())
                    .groupName(group)
                    .build();
        }

        // PostConfirmation trigger: seeds default-deny consent + ACCOUNT_CREATED audit at signup
        // (spec sub-project B). ADR 0004: no VPC anywhere in the platform, so this persistent
        // stack reaches DynamoDB over the regional endpoint via its role, same as every other Lambda.
        var postConfirmationRole = Role.Builder.create(this, "PostConfirmationLambdaRole")
                .roleName("financial-postconfirmation-lambda-role-" + env)
                .description("IAM role for the Cognito PostConfirmation consent-seeding Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();
        data.getPlatformTable().grantReadWriteData(postConfirmationRole);
        data.getAuditTable().grant(postConfirmationRole, "dynamodb:PutItem");
        data.getEncryptionKey().grantEncryptDecrypt(postConfirmationRole);

        var postConfirmationFn = Function.Builder.create(this, "PostConfirmationFn")
                .functionName("financial-postconfirmation-" + env)
                .description("Cognito PostConfirmation: seed default-deny consent + audit event")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/consent-function/target/consent-function.jar"))
                .role(postConfirmationRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("AUDIT_TABLE", data.getAuditTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("CONSENT_POLICY_VERSION", "v1"),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "postConfirmation"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.consent.ConsentHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "PostConfirmationFnLogs")
                .logGroupName("/aws/lambda/" + postConfirmationFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        userPool.addTrigger(UserPoolOperation.POST_CONFIRMATION, postConfirmationFn);

        // PreAuthentication trigger: login gate (spec s11, adapted). Denies WITHDRAWN consent and
        // GIVEN-under-a-stale-version consent by throwing; PENDING (never consented) and current-
        // version GIVEN both allow. Same jar as postConfirmation, its own function definition, same
        // non-VPC placement (reads/writes the platform table over the regional endpoint).
        var preAuthenticationRole = Role.Builder.create(this, "PreAuthenticationLambdaRole")
                .roleName("financial-preauthentication-lambda-role-" + env)
                .description("IAM role for the Cognito PreAuthentication consent-gate Lambda")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSXRayDaemonWriteAccess")))
                .build();
        data.getPlatformTable().grant(preAuthenticationRole, "dynamodb:GetItem");
        data.getAuditTable().grant(preAuthenticationRole, "dynamodb:PutItem");
        data.getEncryptionKey().grantEncryptDecrypt(preAuthenticationRole);

        var preAuthenticationFn = Function.Builder.create(this, "PreAuthenticationFn")
                .functionName("financial-preauthentication-" + env)
                .description("Cognito PreAuthentication: consent login gate")
                .runtime(Runtime.JAVA_25)
                .handler("org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest")
                .code(Code.fromAsset("../functions/consent-function/target/consent-function.jar"))
                .role(preAuthenticationRole)
                .memorySize(512)
                .timeout(Duration.seconds(10))
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .environment(OrderedMap.of(
                        Map.entry("PLATFORM_TABLE", data.getPlatformTable().getTableName()),
                        Map.entry("AUDIT_TABLE", data.getAuditTable().getTableName()),
                        Map.entry("ENVIRONMENT", env),
                        Map.entry("CONSENT_POLICY_VERSION", "v1"),
                        Map.entry("SPRING_CLOUD_FUNCTION_DEFINITION", "preAuthentication"),
                        Map.entry("MAIN_CLASS", "dev.engnotes.consent.ConsentHandler"),
                        Map.entry("LOG_LEVEL", env.equals("prod") ? "INFO" : "DEBUG")))
                .build();

        LogGroup.Builder.create(this, "PreAuthenticationFnLogs")
                .logGroupName("/aws/lambda/" + preAuthenticationFn.getFunctionName())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        userPool.addTrigger(UserPoolOperation.PRE_AUTHENTICATION, preAuthenticationFn);

        this.userPoolClient = userPool.addClient(
                "AppClient",
                UserPoolClientOptions.builder()
                        .userPoolClientName("financial-platform-client-" + env)
                        .generateSecret(false)
                        .authFlows(AuthFlow.builder()
                                .userPassword(true)
                                .userSrp(true)
                                .build())
                        .oAuth(OAuthSettings.builder()
                                .flows(OAuthFlows.builder()
                                        .authorizationCodeGrant(true)
                                        .build())
                                .scopes(List.of(OAuthScope.OPENID, OAuthScope.EMAIL, OAuthScope.PROFILE))
                                // Dev signs in from local dev AND the Amplify-hosted app; both
                                // redirect URIs must be registered or the Hosted UI rejects the
                                // request with redirect_mismatch.
                                .callbackUrls(
                                        prod
                                                ? List.of("https://engnotes.dev/callback")
                                                : List.of(
                                                        "http://localhost:3000/callback",
                                                        "https://main.dzeyw15qwrc7u.amplifyapp.com/callback"))
                                .logoutUrls(
                                        prod
                                                ? List.of("https://engnotes.dev")
                                                : List.of(
                                                        "http://localhost:3000",
                                                        "https://main.dzeyw15qwrc7u.amplifyapp.com"))
                                .build())
                        .build());

        userPool.addDomain(
                "HostedUiDomain",
                UserPoolDomainOptions.builder()
                        .cognitoDomain(CognitoDomainOptions.builder()
                                .domainPrefix("financial-platform-" + env + "-" + this.getAccount())
                                .build())
                        .build());

        new CfnOutput(
                this,
                "UserPoolId",
                CfnOutputProps.builder()
                        .exportName("platform-user-pool-id-" + env)
                        .value(userPool.getUserPoolId())
                        .build());
        new CfnOutput(
                this,
                "UserPoolClientId",
                CfnOutputProps.builder()
                        .exportName("platform-user-pool-client-id-" + env)
                        .value(userPoolClient.getUserPoolClientId())
                        .build());
    }

    public UserPool getUserPool() {
        return userPool;
    }

    public String getUserPoolId() {
        return userPool.getUserPoolId();
    }

    public String getUserPoolClientId() {
        return userPoolClient.getUserPoolClientId();
    }
}
