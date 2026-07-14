package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    static Template synthProd() {
        var app = new App();
        var props = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-south-1")
                        .build())
                .build();
        var data = new DataStack(app, "Data", props, "prod");
        var security = new SecurityStack(app, "Security", props, "prod", data);
        var network = new NetworkStack(app, "Network", props, "prod");
        var ingestion = new IngestionStack(app, "Ingestion", props, "prod", network, data);
        var query = new QueryStack(app, "Query", props, "prod", network, data, ingestion, security);
        return Template.fromStack(query);
    }

    // AWS rejects provisioned concurrency on a SnapStart-enabled function version/alias (a version
    // published while SnapStart is ON_PUBLISHED_VERSIONS). QueryFnAlias used to set
    // provisionedConcurrentExecutions(2) prod-only on the same alias SnapStart requires, which would
    // fail the first prod deploy (found 2026-07-12, STATUS.md "Known gaps"). Assert the combination
    // never recurs, on any Lambda in this stack, under prod context.
    @Test
    @SuppressWarnings("unchecked")
    void noSnapStartFunctionCombinesWithProvisionedConcurrency() {
        var template = synthProd();

        var functions = template.findResources("AWS::Lambda::Function");
        var snapStartFunctionLogicalIds = functions.entrySet().stream()
                .filter(e -> {
                    var props = (Map<String, Object>) e.getValue().get("Properties");
                    return props.get("SnapStart") != null;
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        assertFalse(snapStartFunctionLogicalIds.isEmpty(), "expected at least one SnapStart-enabled function");

        var aliases = template.findResources("AWS::Lambda::Alias");
        var provisionedAliases = aliases.entrySet().stream()
                .filter(e -> {
                    var props = (Map<String, Object>) e.getValue().get("Properties");
                    return props.get("ProvisionedConcurrencyConfig") != null;
                })
                .toList();

        for (var entry : provisionedAliases) {
            var props = (Map<String, Object>) entry.getValue().get("Properties");
            var functionRef = (Map<String, Object>) props.get("FunctionName");
            var functionLogicalId = (String) functionRef.get("Ref");
            assertFalse(
                    snapStartFunctionLogicalIds.contains(functionLogicalId),
                    "alias " + entry.getKey() + " combines provisioned concurrency with SnapStart function "
                            + functionLogicalId);
        }
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

    // Integration responses are only half of non-proxy CORS: API Gateway also requires the method's
    // own MethodResponses to declare the header (as a boolean placeholder), or it strips the header
    // the integration response tried to set before it reaches the client.
    @Test
    @SuppressWarnings("unchecked")
    void allMethodResponsesDeclareCorsAllowOriginHeader() {
        var methods = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> !"OPTIONS".equals(p.get("HttpMethod")))
                .toList();
        assertFalse(methods.isEmpty(), "expected non-OPTIONS API Gateway methods");
        for (var props : methods) {
            var methodResponses = (List<Map<String, Object>>) props.get("MethodResponses");
            assertFalse(methodResponses.isEmpty(), "expected method responses on " + props);
            for (var response : methodResponses) {
                var responseParameters = (Map<String, Object>) response.get("ResponseParameters");
                assertTrue(
                        responseParameters != null
                                && responseParameters.containsKey("method.response.header.Access-Control-Allow-Origin"),
                        "expected Access-Control-Allow-Origin on method response " + response + " of "
                                + props.get("HttpMethod") + " " + props.get("ResourceId"));
            }
        }
    }

    // The 60s stage cache has no caller identity in its key by default: one user's cached GET could
    // be served to another for up to 60s. `/watchlist`, `/user/consent`, `/user/export` all return
    // caller-scoped data, so Authorization must be part of the cache key on each (found 2026-07-13,
    // STATUS.md "Recommended next steps" item 0, LEARNING-GUIDE 13.9).
    @Test
    @SuppressWarnings("unchecked")
    void userScopedGetMethodsCacheKeyOnAuthorizationHeader() {
        var methods = userScopedGetMethods();
        assertEquals(
                3, methods.size(), "expected LIST/VIEW/EXPORT GET methods (/watchlist, /user/consent, /user/export)");
        for (var props : methods) {
            var integration = (Map<String, Object>) props.get("Integration");
            var cacheKeyParameters = (List<String>) integration.get("CacheKeyParameters");
            assertTrue(
                    cacheKeyParameters != null && cacheKeyParameters.contains("method.request.header.Authorization"),
                    "expected Authorization cache key on " + props.get("ResourceId") + ": " + integration);

            var requestParameters = (Map<String, Object>) props.get("RequestParameters");
            assertEquals(
                    Boolean.TRUE,
                    requestParameters == null ? null : requestParameters.get("method.request.header.Authorization"),
                    "expected required Authorization request parameter on " + props.get("ResourceId"));
        }
    }

    // /user/export additionally varies by the optional subjectSub query param (admin-on-behalf):
    // without it in the cache key, the same admin token exporting subject A then subject B within
    // the 60s TTL gets A's cached response for B.
    @Test
    @SuppressWarnings("unchecked")
    void exportCacheKeyIncludesAuthorizationAndSubjectSub() {
        var exports = userScopedGetMethods().stream()
                .filter(p -> requestTemplateBody(p).contains("\"operation\": \"EXPORT\""))
                .toList();
        assertEquals(1, exports.size(), "expected exactly one /user/export GET method");
        var integration = (Map<String, Object>) exports.get(0).get("Integration");
        var cacheKeyParameters = (List<String>) integration.get("CacheKeyParameters");
        assertTrue(
                cacheKeyParameters != null
                        && cacheKeyParameters.contains("method.request.header.Authorization")
                        && cacheKeyParameters.contains("method.request.querystring.subjectSub"),
                "expected Authorization + subjectSub cache keys on /user/export: " + cacheKeyParameters);

        var requestParameters = (Map<String, Object>) exports.get(0).get("RequestParameters");
        assertEquals(
                Boolean.FALSE,
                requestParameters.get("method.request.querystring.subjectSub"),
                "subjectSub must stay optional (self-service calls omit it)");
    }

    // Ticker-scoped routes serve the same data to every caller, so their existing {ticker}-only
    // cache key must not change.
    @Test
    @SuppressWarnings("unchecked")
    void tickerScopedGetMethodsKeepTickerOnlyCacheKey() {
        var methods = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> "GET".equals(p.get("HttpMethod")))
                .filter(p -> requestTemplateBody(p).contains("$input.params('ticker')"))
                .filter(p -> !requestTemplateBody(p).contains("\"operation\""))
                .toList();
        assertEquals(2, methods.size(), "expected /insights/{ticker} and /market-data/{ticker} GET methods");
        for (var props : methods) {
            var integration = (Map<String, Object>) props.get("Integration");
            var cacheKeyParameters = (List<String>) integration.get("CacheKeyParameters");
            assertEquals(List.of("method.request.path.ticker"), cacheKeyParameters);
        }
    }

    // API Gateway only activates caching for GET methods by default even when the stage-level
    // MethodSettings override targets HttpMethod=* (AWS docs: "only GET methods have caching
    // activated to ensure API safety... unless overridden"). Confirm this stack never adds a
    // method-specific override that would turn caching on for a write (POST/DELETE) method.
    @Test
    @SuppressWarnings("unchecked")
    void noMethodSettingOverrideEnablesCachingForWriteMethods() {
        var stages = synth().findResources("AWS::ApiGateway::Stage").values().stream()
                .map(r -> (Map<String, Object>) r.get("Properties"))
                .toList();
        assertEquals(1, stages.size());
        var methodSettings = (List<Map<String, Object>>) stages.get(0).get("MethodSettings");
        assertEquals(1, methodSettings.size(), "expected only the single default */* MethodSetting");
        assertEquals("*", methodSettings.get(0).get("HttpMethod"));

        var writeMethods = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> "POST".equals(p.get("HttpMethod")) || "DELETE".equals(p.get("HttpMethod")))
                .toList();
        assertFalse(writeMethods.isEmpty());
        for (var props : writeMethods) {
            var integration = (Map<String, Object>) props.get("Integration");
            assertTrue(
                    integration.get("CacheKeyParameters") == null,
                    "write method must not declare cache key parameters: " + props.get("ResourceId"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> userScopedGetMethods() {
        return synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> "GET".equals(p.get("HttpMethod")))
                .filter(p -> {
                    var body = requestTemplateBody(p);
                    return body.contains("\"operation\": \"LIST\"")
                            || body.contains("\"operation\": \"VIEW\"")
                            || body.contains("\"operation\": \"EXPORT\"");
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static String requestTemplateBody(Map<String, Object> methodProps) {
        var integration = (Map<String, Object>) methodProps.get("Integration");
        if (integration == null) {
            return "";
        }
        var requestTemplates = (Map<String, Object>) integration.get("RequestTemplates");
        if (requestTemplates == null) {
            return "";
        }
        var body = requestTemplates.get("application/json");
        return body == null ? "" : body.toString();
    }

    // Authorizer 401/403 (ACCESS_DENIED/UNAUTHORIZED, both DEFAULT_4XX) and any DEFAULT_5XX are
    // Gateway Responses, generated outside the per-method Integration/MethodResponses wiring above:
    // without an explicit Gateway Response, they carry no CORS header at all.
    @Test
    @SuppressWarnings("unchecked")
    void hasCorsHeaderOnDefaultGatewayResponses() {
        var gatewayResponses = synth().findResources("AWS::ApiGateway::GatewayResponse").values().stream()
                .map(r -> (Map<String, Object>) r.get("Properties"))
                .toList();
        assertEquals(2, gatewayResponses.size(), "expected DEFAULT_4XX and DEFAULT_5XX gateway responses");

        var types = gatewayResponses.stream()
                .map(p -> (String) p.get("ResponseType"))
                .sorted()
                .toList();
        assertEquals(List.of("DEFAULT_4XX", "DEFAULT_5XX"), types);

        for (var props : gatewayResponses) {
            var responseParameters = (Map<String, Object>) props.get("ResponseParameters");
            assertTrue(
                    responseParameters != null
                            && responseParameters.containsKey("gatewayresponse.header.Access-Control-Allow-Origin"),
                    "expected Access-Control-Allow-Origin on gateway response " + props);
        }
    }
}
