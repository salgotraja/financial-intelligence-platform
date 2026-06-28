package dev.engnotes.platform.stacks;

import java.util.List;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cognito.*;
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

    public SecurityStack(final Construct scope, final String id, final StackProps props, final String env) {
        super(scope, id, props);

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
                .customAttributes(java.util.Map.of(
                        "consent_given",
                                StringAttribute.Builder.create().mutable(true).build(),
                        "consent_timestamp",
                                StringAttribute.Builder.create().mutable(true).build(),
                        "consent_version",
                                StringAttribute.Builder.create().mutable(true).build(),
                        "data_processing_purpose",
                                StringAttribute.Builder.create().mutable(true).build()))
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
                                .callbackUrls(List.of(
                                        prod ? "https://engnotes.dev/callback" : "http://localhost:3000/callback"))
                                .logoutUrls(List.of(prod ? "https://engnotes.dev" : "http://localhost:3000"))
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
