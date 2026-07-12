package dev.engnotes.platform.stacks;

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

    @Test
    void notifierConsumesInsertEventsFromTheTableStream() {
        synth().hasResourceProperties(
                        "AWS::Lambda::EventSourceMapping",
                        Match.objectLike(Map.of(
                                "FilterCriteria",
                                Map.of("Filters", List.of(Map.of("Pattern", "{\"eventName\":[\"INSERT\"]}"))))));
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
}
