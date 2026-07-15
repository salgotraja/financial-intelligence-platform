package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class RealtimeStackTest {

    static Template synth() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();
        var data = new DataStack(app, "Data", props, "dev");
        var security = new SecurityStack(app, "Security", props, "dev", data);
        var realtime = new RealtimeStack(app, "Realtime", props, "dev", data, security);
        return Template.fromStack(realtime);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasConnectDisconnectAndSubscribeRoutes() {
        var routeKeys = synth().findResources("AWS::ApiGatewayV2::Route").values().stream()
                .map(r -> (Map<String, Object>) r.get("Properties"))
                .map(p -> (String) p.get("RouteKey"))
                .toList();
        assertTrue(routeKeys.contains("$connect"), "missing $connect route");
        assertTrue(routeKeys.contains("$disconnect"), "missing $disconnect route");
        assertTrue(routeKeys.contains("subscribe"), "missing subscribe route");
    }

    @Test
    void connectIsGuardedByRequestAuthorizerOnTokenQuerystring() {
        synth().hasResourceProperties(
                        "AWS::ApiGatewayV2::Authorizer",
                        Match.objectLike(Map.of(
                                "AuthorizerType",
                                "REQUEST",
                                "IdentitySource",
                                List.of("route.request.querystring.token"))));
    }

    // Item 4: the event-source filter itself excludes market-data INSERTs (SK begins_with
    // INSIGHT#), not just the code-level check in the notifier, so the Lambda is never invoked for
    // the far more numerous market-data writes. The "Pattern" property is a JSON-encoded string, so
    // this uses Match.serializedJson to compare its structure regardless of the key order Map.of()
    // happens to iterate in (unspecified and JVM-salted, unlike a literal string comparison).
    @Test
    void notifierConsumesOnlyInsightInsertEventsFromTheTableStream() {
        var skBeginsWithInsight = Map.of("S", List.of(Map.of("prefix", "INSIGHT#")));
        var expectedPattern =
                Map.of("eventName", List.of("INSERT"), "dynamodb", Map.of("Keys", Map.of("SK", skBeginsWithInsight)));

        synth().hasResourceProperties(
                        "AWS::Lambda::EventSourceMapping",
                        Match.objectLike(Map.of(
                                "FilterCriteria",
                                Map.of("Filters", List.of(Map.of("Pattern", Match.serializedJson(expectedPattern)))))));
    }

    @Test
    void connectionsTableHasTtlAndByConnectionGsi() {
        synth().hasResourceProperties(
                        "AWS::DynamoDB::Table",
                        Match.objectLike(Map.of(
                                "TableName", "financial-connections-dev",
                                "TimeToLiveSpecification", Map.of("AttributeName", "ttl", "Enabled", true),
                                "GlobalSecondaryIndexes",
                                        List.of(Match.objectLike(Map.of("IndexName", "by-connection"))))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allWebSocketIntegrationsInvokeLiveAliases() {
        var uris = synth().findResources("AWS::ApiGatewayV2::Integration").values().stream()
                .map(r -> (Map<String, Object>) r.get("Properties"))
                .map(p -> p.get("IntegrationUri").toString())
                .toList();
        assertTrue(!uris.isEmpty(), "expected websocket integrations");
        for (var uri : uris) {
            assertTrue(uri.contains("live") || uri.contains("Alias"), "integration must target the live alias: " + uri);
        }
    }

    // Item 5: the WebSocket $connect authorizer must also target a published-version "live" alias,
    // not $LATEST, or SnapStart never engages for it either.
    @Test
    @SuppressWarnings("unchecked")
    void connectAuthorizerTargetsLiveAlias() {
        var template = synth();
        var aliases = template.findResources("AWS::Lambda::Alias");

        var authorizers = template.findResources("AWS::ApiGatewayV2::Authorizer");
        assertEquals(1, authorizers.size(), "expected exactly one WebSocket $connect authorizer");
        var entry = authorizers.entrySet().iterator().next();
        var props = (Map<String, Object>) entry.getValue().get("Properties");
        var uri = (Map<String, Object>) props.get("AuthorizerUri");
        LiveAliasAssertions.assertUriTargetsLiveAlias(entry.getKey(), uri, aliases);
    }

    // Spec s11 erasure step 1: $connect/subscribe must be able to read USER#{sub}/PROFILE on the
    // platform table to refuse registration for a deletion-pending caller.
    @Test
    void manageConnectionFnCanReadThePlatformTable() {
        var envVars = Map.of("Variables", Match.objectLike(Map.of("PLATFORM_TABLE", Match.anyValue())));
        synth().hasResourceProperties(
                        "AWS::Lambda::Function",
                        Match.objectLike(
                                Map.of("FunctionName", "financial-manage-connection-dev", "Environment", envVars)));

        var readStatement = Match.objectLike(Map.of("Action", Match.arrayWith(List.of("dynamodb:GetItem"))));
        var policyDocument = Match.objectLike(Map.of("Statement", Match.arrayWith(List.of(readStatement))));
        var roleRef = Map.of("Ref", Match.stringLikeRegexp("ManageConnectionLambdaRole.*"));
        synth().hasResourceProperties(
                        "AWS::IAM::Policy",
                        Match.objectLike(Map.of("Roles", List.of(roleRef), "PolicyDocument", policyDocument)));
    }
}
