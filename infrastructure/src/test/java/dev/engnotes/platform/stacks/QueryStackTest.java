package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class QueryStackTest {

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
        var network = new NetworkStack(app, "Network", props, "dev");
        var ingestion = new IngestionStack(app, "Ingestion", props, "dev", network, data);
        var query = new QueryStack(app, "Query", props, "dev", network, data, ingestion, security);
        return Template.fromStack(query);
    }

    @Test
    void has5xxRatePageAlarm() {
        synth().hasResourceProperties(
                        "AWS::CloudWatch::Alarm", Match.objectLike(Map.of("AlarmName", "financial-api-5xx-rate-dev")));
    }

    @Test
    void hasPlatformDashboard() {
        synth().resourceCountIs("AWS::CloudWatch::Dashboard", 1);
    }

    // API Gateway evaluates selection patterns against an EMPTY errorMessage on successful
    // invocations, so a 500 pattern matching "" hijacks every 200 into a 500 (real-AWS-only bug).
    @Test
    @SuppressWarnings("unchecked")
    void serverErrorSelectionPatternNeverMatchesSuccessOrClientErrors() {
        var patterns = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .map(p -> (Map<String, Object>) p.get("Integration"))
                .filter(i -> i != null && i.get("IntegrationResponses") instanceof List)
                .flatMap(i -> ((List<Map<String, Object>>) i.get("IntegrationResponses")).stream())
                .filter(r -> "500".equals(r.get("StatusCode")))
                .map(r -> (String) r.get("SelectionPattern"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        assertFalse(patterns.isEmpty(), "expected a 500 selection pattern on protected methods");
        for (var p : patterns) {
            var regex = Pattern.compile(p);
            assertFalse(regex.matcher("").matches(), "500 pattern must not match empty success errorMessage");
            assertTrue(regex.matcher("java.lang.IllegalStateException: boom").matches());
            assertFalse(regex.matcher("Invalid ticker: 123").matches(), "client errors must map to 400, not 500");
        }
    }

    @Test
    void p99AlarmPagesCriticalTopicWithSustainedWindow() {
        synth().hasResourceProperties(
                        "AWS::CloudWatch::Alarm",
                        Match.objectLike(Map.of(
                                "AlarmName", "financial-api-p99-latency-dev",
                                "EvaluationPeriods", 5,
                                "DatapointsToAlarm", 5)));
    }

    @Test
    void hasMarketDataLambdaServingServeMarketData() {
        synth().hasResourceProperties(
                        "AWS::Lambda::Function",
                        Match.objectLike(Map.of(
                                "FunctionName",
                                "financial-market-data-dev",
                                "Environment",
                                Match.objectLike(Map.of(
                                        "Variables",
                                        Match.objectLike(
                                                Map.of("SPRING_CLOUD_FUNCTION_DEFINITION", "serveMarketData")))))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasMarketDataResourceUnderApiRoot() {
        var pathParts = synth().findResources("AWS::ApiGateway::Resource").values().stream()
                .map(r -> (Map<String, Object>) r.get("Properties"))
                .map(p -> (String) p.get("PathPart"))
                .toList();
        assertTrue(pathParts.contains("market-data"), "expected a market-data API resource");
    }

    // The OPTIONS preflight response carries Access-Control-Allow-Origin (CDK's
    // defaultCorsPreflightOptions handles that), but browsers also read the header off the actual
    // GET/POST/DELETE response. Non-proxy integrations never emit it unless every IntegrationResponse
    // maps it explicitly, so every real method (everything but the CORS-generated OPTIONS) must carry it.
    @Test
    @SuppressWarnings("unchecked")
    void allResponsesCarryCorsAllowOriginHeader() {
        var methods = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> !"OPTIONS".equals(p.get("HttpMethod")))
                .toList();
        assertFalse(methods.isEmpty(), "expected non-OPTIONS API Gateway methods");
        for (var props : methods) {
            var integration = (Map<String, Object>) props.get("Integration");
            var integrationResponses = (List<Map<String, Object>>) integration.get("IntegrationResponses");
            assertFalse(integrationResponses.isEmpty(), "expected integration responses on " + props);
            for (var response : integrationResponses) {
                var responseParameters = (Map<String, Object>) response.get("ResponseParameters");
                assertTrue(
                        responseParameters != null
                                && responseParameters.containsKey("method.response.header.Access-Control-Allow-Origin"),
                        "expected Access-Control-Allow-Origin on integration response " + response + " of "
                                + props.get("HttpMethod") + " " + props.get("ResourceId"));
            }
        }
    }
}
