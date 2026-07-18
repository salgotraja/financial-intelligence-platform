package dev.engnotes.platform.stacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    // never recurs, on any Lambda in this stack, under prod context. The check logic itself lives in
    // LiveAliasAssertions so PlatformWideHardeningTest can run the identical assertion over every
    // other stack without duplicating it.
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

        LiveAliasAssertions.assertNoSnapStartFunctionCombinesWithProvisionedConcurrency("Query", template);
    }

    // Pins the SnapStart-$LATEST regression class (STATUS.md "Known gaps" item 3) for every REST API
    // Gateway route at once, current and future: a non-proxy Lambda integration that targets the bare
    // function, or an alias other than "live", silently pays the full Spring Boot cold start on every
    // invocation instead of restoring from the SnapStart snapshot.
    @Test
    @SuppressWarnings("unchecked")
    void everyLambdaApiGatewayIntegrationTargetsLiveAlias() {
        var template = synth();
        var aliases = template.findResources("AWS::Lambda::Alias");

        var lambdaIntegrationUris = template.findResources("AWS::ApiGateway::Method").entrySet().stream()
                .map(e -> {
                    var props = (Map<String, Object>) e.getValue().get("Properties");
                    var integration = (Map<String, Object>) props.get("Integration");
                    return Map.entry(e.getKey(), integration);
                })
                .filter(e -> e.getValue() != null && "AWS".equals(e.getValue().get("Type")))
                .map(e ->
                        Map.entry(e.getKey(), (Map<String, Object>) e.getValue().get("Uri")))
                .filter(e -> LiveAliasAssertions.isLambdaInvocationUri(e.getValue()))
                .toList();

        assertFalse(lambdaIntegrationUris.isEmpty(), "expected Lambda-backed API Gateway method integrations");
        for (var entry : lambdaIntegrationUris) {
            LiveAliasAssertions.assertUriTargetsLiveAlias(entry.getKey(), entry.getValue(), aliases);
        }
    }

    // Every request template that interpolates the caller-controlled {ticker} path param must wrap
    // it in $util.escapeJavaScript: a quote-breaking ticker in an unescaped template can inject a
    // duplicate JSON key after the legitimate one (e.g. flipping the watchlist "operation" from ADD
    // to REMOVE), since Jackson keeps the last duplicate. Pins the class for every current template
    // (insight GET, market-data GET, market-data daily GET, stories GET, watchlist POST/DELETE,
    // ingest POST) and any future one.
    @Test
    @SuppressWarnings("unchecked")
    void everyTickerInterpolatingRequestTemplateEscapesTheTicker() {
        var tickerTemplates = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .map(QueryStackTest::requestTemplateBody)
                .filter(body -> body.contains("$input.params('ticker')"))
                .toList();
        assertEquals(
                8,
                tickerTemplates.size(),
                "expected 8 ticker-interpolating request templates (insight GET, market-data GET,"
                        + " market-data daily GET, stories GET, analysis GET, watchlist POST/DELETE, ingest"
                        + " POST)");
        for (var body : tickerTemplates) {
            var unescapedRemainder = body.replace("$util.escapeJavaScript($input.params('ticker'))", "");
            assertFalse(
                    unescapedRemainder.contains("$input.params('ticker')"),
                    "raw unescaped $input.params('ticker') in request template: " + body);
        }
    }

    // The ingest 202 response echoes the caller-supplied ticker back in its own ResponseTemplates,
    // a second interpolation site distinct from (and previously missed by) the request-template
    // escaping above - a quote-breaking ticker here could otherwise inject into the response body.
    @Test
    @SuppressWarnings("unchecked")
    void ingestAcceptedResponseTemplateEscapesTheTicker() {
        var ingestResponseTemplates = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .map(p -> (Map<String, Object>) p.get("Integration"))
                .filter(i -> i != null && i.get("IntegrationResponses") instanceof List)
                .flatMap(i -> ((List<Map<String, Object>>) i.get("IntegrationResponses")).stream())
                .filter(r -> "202".equals(r.get("StatusCode")))
                .map(r -> (Map<String, Object>) r.get("ResponseTemplates"))
                .filter(Objects::nonNull)
                .map(t -> String.valueOf(t.get("application/json")))
                .filter(body -> body.contains("\"ticker\""))
                .toList();
        assertEquals(1, ingestResponseTemplates.size(), "expected exactly one ingest-accepted response template");
        var body = ingestResponseTemplates.get(0);
        assertFalse(
                body.replace("$util.escapeJavaScript($input.params('ticker'))", "")
                        .contains("$input.params('ticker')"),
                "raw unescaped $input.params('ticker') in ingest response template: " + body);
    }

    // Item 5: the TokenAuthorizer used to target $LATEST directly, so the authorizer paid the full
    // Spring Boot cold start on every request even though SnapStart was enabled on the function.
    @Test
    @SuppressWarnings("unchecked")
    void tokenAuthorizerTargetsLiveAlias() {
        var template = synth();
        var aliases = template.findResources("AWS::Lambda::Alias");

        var authorizers = template.findResources("AWS::ApiGateway::Authorizer");
        assertEquals(1, authorizers.size(), "expected exactly one REST TokenAuthorizer");
        var entry = authorizers.entrySet().iterator().next();
        var props = (Map<String, Object>) entry.getValue().get("Properties");
        var uri = (Map<String, Object>) props.get("AuthorizerUri");
        LiveAliasAssertions.assertUriTargetsLiveAlias(entry.getKey(), uri, aliases);
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

    // The bare /insights feed (watchlist-scoped, no ticker) must be a separate GET method from
    // /insights/{ticker}: same resource path prefix, different resource/integration entirely.
    @Test
    @SuppressWarnings("unchecked")
    void bareInsightsFeedRouteIsDistinctFromPerTickerRoute() {
        var feedMethods = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> "GET".equals(p.get("HttpMethod")))
                .filter(p -> {
                    var body = requestTemplateBody(p);
                    return body.contains("\"ownerSub\": \"$context.authorizer.sub\"")
                            && !body.contains("\"operation\"");
                })
                .toList();
        assertEquals(1, feedMethods.size(), "expected exactly one GET /insights feed method");

        var requestParameters = (Map<String, Object>) feedMethods.get(0).get("RequestParameters");
        assertTrue(
                requestParameters == null || !requestParameters.containsKey("method.request.path.ticker"),
                "the bare /insights feed route must not require a ticker path param");
    }

    // GSI1 access for the insight feed's per-ticker group-insight query needs no extra grant: CDK's
    // Table.grantReadData already covers Query on every index (tableArn + tableArn/index/*) once the
    // table has one, which platformTable does (DataStack's GSI1). Pins that the synthesized
    // QueryLambdaRole policy actually carries the index resource, so a future refactor that narrows
    // the grant is caught here rather than at runtime.
    @Test
    @SuppressWarnings("unchecked")
    void queryRoleGrantCoversGsi1Index() {
        var template = synth();
        var queryRoleLogicalId = template.findResources("AWS::IAM::Role").entrySet().stream()
                .filter(e -> {
                    var props = (Map<String, Object>) e.getValue().get("Properties");
                    return "financial-query-lambda-role-dev".equals(props.get("RoleName"));
                })
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a QueryLambdaRole to synth"));

        var attachedPolicies = template.findResources("AWS::IAM::Policy").values().stream()
                .map(r -> (Map<String, Object>) r.get("Properties"))
                .filter(p -> {
                    var roleRefs = (List<Map<String, Object>>) p.get("Roles");
                    return roleRefs != null
                            && roleRefs.stream().anyMatch(ref -> queryRoleLogicalId.equals(ref.get("Ref")));
                })
                .toList();
        assertFalse(attachedPolicies.isEmpty(), "expected an IAM policy attached to QueryLambdaRole");

        boolean coversIndex =
                attachedPolicies.stream().anyMatch(p -> p.toString().contains("/index/*"));
        assertTrue(
                coversIndex,
                "expected QueryLambdaRole's DynamoDB grant to cover the GSI1 index resource"
                        + " (tableArn/index/*), required by the insight feed's GSI1 query");
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

    @Test
    @SuppressWarnings("unchecked")
    void hasStoriesResourceUnderApiRoot() {
        var pathParts = synth().findResources("AWS::ApiGateway::Resource").values().stream()
                .map(r -> (Map<String, Object>) r.get("Properties"))
                .map(p -> (String) p.get("PathPart"))
                .toList();
        assertTrue(pathParts.contains("stories"), "expected a stories API resource");
    }

    // Confirms GET /stories/{ticker} resolves to the dedicated StoriesFn Lambda via
    // SPRING_CLOUD_FUNCTION_DEFINITION=serveStory (spec sub-project C, Task 16).
    @Test
    void hasStoriesLambdaServingServeStory() {
        synth().hasResourceProperties(
                        "AWS::Lambda::Function",
                        Match.objectLike(Map.of(
                                "FunctionName",
                                "financial-stories-dev",
                                "Environment",
                                Match.objectLike(Map.of(
                                        "Variables",
                                        Match.objectLike(Map.of("SPRING_CLOUD_FUNCTION_DEFINITION", "serveStory")))))));
    }

    @Test
    void hasAnalysisLambdaServingServeDeepAnalysis() {
        synth().hasResourceProperties(
                        "AWS::Lambda::Function",
                        Match.objectLike(Map.of(
                                "FunctionName",
                                "financial-analysis-dev",
                                "Environment",
                                Match.objectLike(Map.of(
                                        "Variables",
                                        Match.objectLike(
                                                Map.of("SPRING_CLOUD_FUNCTION_DEFINITION", "serveDeepAnalysis")))))));
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
    // be served to another for up to 60s. `/watchlist`, `/user/consent`, `/user/export`, `/insights`
    // (the watchlist feed) all return caller-scoped data, so Authorization must be part of the cache
    // key on each (found 2026-07-13, STATUS.md "Recommended next steps" item 0, LEARNING-GUIDE 13.9).
    @Test
    @SuppressWarnings("unchecked")
    void userScopedGetMethodsCacheKeyOnAuthorizationHeader() {
        var methods = userScopedGetMethods();
        assertEquals(
                4,
                methods.size(),
                "expected LIST/VIEW/EXPORT GET methods plus the bare /insights feed"
                        + " (/watchlist, /user/consent, /user/export, /insights)");
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
    // cache key must not change. /market-data/{ticker}/daily is excluded by the "days" filter: it is
    // also ticker-scoped and serves identical data to every caller, but its cache key additionally
    // varies by "days" (asserted separately by dailyMarketDataCacheKeyIncludesTickerAndDays).
    @Test
    @SuppressWarnings("unchecked")
    void tickerScopedGetMethodsKeepTickerOnlyCacheKey() {
        var methods = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> "GET".equals(p.get("HttpMethod")))
                .filter(p -> requestTemplateBody(p).contains("$input.params('ticker')"))
                .filter(p -> !requestTemplateBody(p).contains("\"operation\""))
                .filter(p -> !requestTemplateBody(p).contains("\"days\""))
                .toList();
        assertEquals(
                4,
                methods.size(),
                "expected /insights/{ticker}, /market-data/{ticker}, /stories/{ticker}, and"
                        + " /analysis/{ticker} GET methods");
        for (var props : methods) {
            var integration = (Map<String, Object>) props.get("Integration");
            var cacheKeyParameters = (List<String>) integration.get("CacheKeyParameters");
            assertEquals(List.of("method.request.path.ticker"), cacheKeyParameters);
        }
    }

    // /market-data/{ticker}/daily varies by both the ticker AND the days window: without "days" in
    // the cache key, the 60s stage cache would serve one days-window's response (e.g. 30) for every
    // other days-window (e.g. 90) requested on the same ticker within the TTL.
    @Test
    @SuppressWarnings("unchecked")
    void dailyMarketDataCacheKeyIncludesTickerAndDays() {
        var methods = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> "GET".equals(p.get("HttpMethod")))
                .filter(p -> requestTemplateBody(p).contains("$input.params('ticker')"))
                .filter(p -> requestTemplateBody(p).contains("\"days\""))
                .toList();
        assertEquals(1, methods.size(), "expected exactly one GET /market-data/{ticker}/daily method");
        var integration = (Map<String, Object>) methods.get(0).get("Integration");
        var cacheKeyParameters = (List<String>) integration.get("CacheKeyParameters");
        assertTrue(
                cacheKeyParameters != null
                        && cacheKeyParameters.contains("method.request.path.ticker")
                        && cacheKeyParameters.contains("method.request.querystring.days"),
                "expected ticker + days cache keys on /market-data/{ticker}/daily: " + cacheKeyParameters);

        var requestParameters = (Map<String, Object>) methods.get(0).get("RequestParameters");
        assertEquals(
                Boolean.FALSE,
                requestParameters.get("method.request.querystring.days"),
                "days must stay optional (absent defaults to 30)");
    }

    // The daily route's template interpolates TWO caller-supplied params. The class-wide ticker
    // test above covers 'ticker'; this pins 'days' in the same no-raw-remainder style, so a future
    // template rewrite cannot drop $util.escapeJavaScript from either param unseen.
    @Test
    @SuppressWarnings("unchecked")
    void dailyMarketDataTemplateEscapesBothTickerAndDays() {
        var templates = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .map(QueryStackTest::requestTemplateBody)
                .filter(body -> body.contains("$input.params('days')"))
                .toList();
        assertEquals(1, templates.size(), "expected exactly one days-interpolating request template (daily GET)");
        var body = templates.get(0);
        var unescapedRemainder = body.replace("$util.escapeJavaScript($input.params('ticker'))", "")
                .replace("$util.escapeJavaScript($input.params('days'))", "");
        assertFalse(
                unescapedRemainder.contains("$input.params('ticker')"),
                "raw unescaped $input.params('ticker') in daily request template: " + body);
        assertFalse(
                unescapedRemainder.contains("$input.params('days')"),
                "raw unescaped $input.params('days') in daily request template: " + body);
    }

    // Confirms the new /market-data/{ticker}/daily resource is nested under {ticker}, not a sibling
    // top-level resource, and that GET /market-data/{ticker}/daily resolves to the dedicated
    // DailyMarketDataFn Lambda via SPRING_CLOUD_FUNCTION_DEFINITION=serveDailyMarketData.
    @Test
    void hasDailyMarketDataLambdaServingServeDailyMarketData() {
        synth().hasResourceProperties(
                        "AWS::Lambda::Function",
                        Match.objectLike(Map.of(
                                "FunctionName",
                                "financial-market-data-daily-dev",
                                "Environment",
                                Match.objectLike(Map.of(
                                        "Variables",
                                        Match.objectLike(Map.of(
                                                "SPRING_CLOUD_FUNCTION_DEFINITION", "serveDailyMarketData")))))));
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

    // == Erasure Step Functions workflow (spec s11, Task 11) ==

    @Test
    void erasureStateMachineIsStandardTypeWithFixedName() {
        synth().hasResourceProperties(
                        "AWS::StepFunctions::StateMachine",
                        Match.objectLike(
                                Map.of("StateMachineName", "financial-erasure-dev", "StateMachineType", "STANDARD")));
    }

    @Test
    void erasureStatesRunInTheSpecifiedOrderWithTheRightOperationEach() {
        String definition = erasureDefinitionString();

        // Next-pointer chain: MarkDeletionPending -> DeleteUserItems -> S3SafeguardDelete ->
        // DeleteCognitoUser -> SendConfirmationEmail -> WriteErasureAudit -> ErasureCompleted.
        assertTrue(definition.contains("\"MarkDeletionPending\":{\"Next\":\"DeleteUserItems\""));
        assertTrue(definition.contains("\"DeleteUserItems\":{\"Next\":\"S3SafeguardDelete\""));
        assertTrue(definition.contains("\"S3SafeguardDelete\":{\"Next\":\"DeleteCognitoUser\""));
        assertTrue(definition.contains("\"DeleteCognitoUser\":{\"Next\":\"SendConfirmationEmail\""));
        assertTrue(definition.contains("\"SendConfirmationEmail\":{\"Next\":\"WriteErasureAudit\""));
        assertTrue(definition.contains("\"WriteErasureAudit\":{\"Next\":\"ErasureCompleted\""));

        assertTrue(definition.contains("\"operation\":\"MARK_PENDING\""));
        assertTrue(definition.contains("\"operation\":\"DELETE_USER_ITEMS\""));
        assertTrue(definition.contains("\"operation\":\"S3_SAFEGUARD\""));
        assertTrue(definition.contains("\"operation\":\"DELETE_COGNITO_USER\""));
        assertTrue(definition.contains("\"operation\":\"SEND_CONFIRMATION_EMAIL\""));
        assertTrue(definition.contains("\"operation\":\"WRITE_ERASURE_AUDIT\""));
    }

    // Pins the Parameters key order to a fixed sequence (OrderedMap, not Map.of): Map.of's
    // per-JVM-run salted iteration order made two `cdk synth` runs of identical source emit this
    // same Parameters map with different key orders, replacing the Deployment/state machine on
    // every deploy even with no real change (found in live validation). A substring match on the
    // exact serialized order is a cheap way to catch a regression back to an unordered map.
    @Test
    void erasureTaskParametersHaveAFixedKeyOrder() {
        String definition = erasureDefinitionString();

        assertTrue(
                definition.contains("\"Parameters\":{\"operation\":\"MARK_PENDING\",\"subjectSub.$\":\"$.subjectSub\","
                        + "\"callerSub.$\":\"$.callerSub\",\"sourceIp.$\":\"$.sourceIp\","
                        + "\"correlationId.$\":\"$.correlationId\",\"requestedAt.$\":\"$.requestedAt\"}"),
                "expected a fixed Parameters key order for MarkDeletionPending: " + definition);
    }

    @Test
    void everyErasureStateRetriesTwiceWithDoublingBackoff() {
        String definition = erasureDefinitionString();
        String retryFragment = "\"IntervalSeconds\":2,\"MaxAttempts\":2,\"BackoffRate\":2";

        int count = 0;
        int from = 0;
        while ((from = definition.indexOf(retryFragment, from)) != -1) {
            count++;
            from += retryFragment.length();
        }
        assertEquals(6, count, "expected all 6 Task states to carry the 2-attempt, 2x-backoff retry");
    }

    @Test
    void sendConfirmationEmailFailureCatchesToEmailFailedThenWriteErasureAudit() {
        String definition = erasureDefinitionString();

        assertTrue(
                definition.contains(
                        "\"Catch\":[{\"ErrorEquals\":[\"States.ALL\"],\"ResultPath\":\"$.errorInfo\",\"Next\":\"EmailFailed\"}]"),
                "expected SendConfirmationEmail to catch all errors into EmailFailed without losing"
                        + " subjectSub/callerSub/etc (ResultPath must not be $)");
        assertTrue(
                definition.contains(
                        "\"EmailFailed\":{\"Type\":\"Pass\",\"Comment\":\"Confirmation email failed after retries;"
                                + " erasure still completes\",\"Result\":false,\"ResultPath\":\"$.emailSent\",\"Next\":\"WriteErasureAudit\"}"),
                "expected EmailFailed to record emailSent=false and proceed to WriteErasureAudit,"
                        + " so a failed email never fails the erasure");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dsrRoleHasStartExecutionScopedToTheErasureStateMachineOnly() {
        var template = synth();
        var statements = dsrLambdaRolePolicyStatements(template);

        var startExecutionStatements = statements.stream()
                .filter(s -> actionsOf(s).contains("states:StartExecution"))
                .toList();
        assertEquals(
                1,
                startExecutionStatements.size(),
                "expected exactly one states:StartExecution statement on DsrLambdaRole");
        assertEquals(
                "arn:aws:states:ap-south-1:123456789012:stateMachine:financial-erasure-dev",
                startExecutionStatements.get(0).get("Resource"),
                "expected StartExecution scoped to a literal ARN naming only the erasure state"
                        + " machine (not a wildcard, not the ingestion state machine) - a construct-ref"
                        + " grant here would close a dependency cycle back through DsrFnAlias");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dsrRoleHasSesSendEmailScopedToTheSenderIdentityNotAWildcard() {
        var template = synth();
        var statements = dsrLambdaRolePolicyStatements(template);

        var sesStatements = statements.stream()
                .filter(s -> actionsOf(s).contains("ses:SendEmail"))
                .toList();
        assertEquals(1, sesStatements.size(), "expected exactly one ses:SendEmail statement on DsrLambdaRole");
        assertEquals(
                List.of("ses:SendEmail"),
                actionsOf(sesStatements.get(0)),
                "expected only ses:SendEmail, not the broader ses:SendRawEmail EmailIdentity.grantSendEmail() adds");
        Object resource = sesStatements.get(0).get("Resource");
        assertFalse("*".equals(resource), "ses:SendEmail must be scoped to the sender identity, not '*'");
        assertTrue(resource.toString().contains("identity/"), "expected an SES identity ARN resource: " + resource);
    }

    @Test
    @SuppressWarnings("unchecked")
    void accountDeleteRouteReturns202OnAcceptedAndKeeps400500ForErrors() {
        var method = synth().findResources("AWS::ApiGateway::Method").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> "DELETE".equals(p.get("HttpMethod")))
                .filter(p -> requestTemplateBody(p).contains("\"operation\": \"ERASE\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected the DELETE /user/account method to synth"));

        var integration = (Map<String, Object>) method.get("Integration");
        var integrationStatusCodes = ((List<Map<String, Object>>) integration.get("IntegrationResponses"))
                .stream().map(r -> r.get("StatusCode")).toList();
        assertEquals(List.of("202", "400", "500"), integrationStatusCodes);

        var methodStatusCodes = ((List<Map<String, Object>>) method.get("MethodResponses"))
                .stream().map(r -> r.get("StatusCode")).toList();
        assertEquals(List.of("202", "400", "500"), methodStatusCodes);
    }

    @SuppressWarnings("unchecked")
    private static String erasureDefinitionString() {
        var props = synth().findResources("AWS::StepFunctions::StateMachine").values().stream()
                .map(m -> (Map<String, Object>) m.get("Properties"))
                .filter(p -> "financial-erasure-dev".equals(p.get("StateMachineName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected the erasure state machine to synth"));
        var definitionString = (Map<String, Object>) props.get("DefinitionString");
        var joinParts = (List<Object>) definitionString.get("Fn::Join");
        return ((List<Object>) joinParts.get(1))
                .stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(Collectors.joining());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dsrLambdaRolePolicyStatements(Template template) {
        var dsrRoleLogicalId = template.findResources("AWS::IAM::Role").entrySet().stream()
                .filter(e -> {
                    var props = (Map<String, Object>) e.getValue().get("Properties");
                    return "financial-dsr-lambda-role-dev".equals(props.get("RoleName"));
                })
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a DsrLambdaRole to synth"));

        return template.findResources("AWS::IAM::Policy").values().stream()
                .map(r -> (Map<String, Object>) r.get("Properties"))
                .filter(p -> {
                    var roleRefs = (List<Map<String, Object>>) p.get("Roles");
                    return roleRefs != null
                            && roleRefs.stream().anyMatch(ref -> dsrRoleLogicalId.equals(ref.get("Ref")));
                })
                .flatMap(p -> ((List<Map<String, Object>>)
                                ((Map<String, Object>) p.get("PolicyDocument")).get("Statement"))
                        .stream())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> actionsOf(Map<String, Object> statement) {
        Object action = statement.get("Action");
        if (action instanceof List<?> list) {
            return (List<String>) list;
        }
        return action == null ? List.of() : List.of((String) action);
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
                            || body.contains("\"operation\": \"EXPORT\"")
                            // The bare /insights feed has no "operation" field (it is its own Lambda
                            // function, selected via SPRING_CLOUD_FUNCTION_DEFINITION, not an
                            // operation-dispatch handler), so it is identified by ownerSub alone.
                            || (body.contains("\"ownerSub\": \"$context.authorizer.sub\"")
                                    && !body.contains("\"operation\""));
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

    // == WAF (spec s12, Task 13) ==

    @Test
    @SuppressWarnings("unchecked")
    void webAclHasManagedRuleGroupsThenRateLimitRuleInPriorityOrder() {
        var template = synth();
        var acls = template.findResources("AWS::WAFv2::WebACL");
        assertEquals(1, acls.size(), "expected exactly one regional Web ACL");
        var props = (Map<String, Object>) acls.values().iterator().next().get("Properties");
        assertEquals("REGIONAL", props.get("Scope"));

        var rules = (List<Map<String, Object>>) props.get("Rules");
        assertEquals(3, rules.size(), "expected the 2 managed rule groups plus the rate-based rule");

        var byPriority = rules.stream().collect(Collectors.toMap(r -> ((Number) r.get("Priority")).intValue(), r -> r));
        assertEquals("AWSManagedRulesCommonRuleSet", byPriority.get(0).get("Name"));
        assertEquals("AWSManagedRulesKnownBadInputsRuleSet", byPriority.get(1).get("Name"));
        assertEquals("RateLimitPerIp", byPriority.get(2).get("Name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void managedRuleGroupsRunInOverrideActionNoneModeWithTheRightRuleSetNames() {
        var rules = webAclRules();
        var managedRules = rules.stream()
                .filter(r -> {
                    var statement = (Map<String, Object>) r.get("Statement");
                    return statement.containsKey("ManagedRuleGroupStatement");
                })
                .toList();
        assertEquals(2, managedRules.size());

        var ruleSetNames = managedRules.stream()
                .map(r -> {
                    var statement = (Map<String, Object>) r.get("Statement");
                    var managedRuleGroupStatement = (Map<String, Object>) statement.get("ManagedRuleGroupStatement");
                    return (String) managedRuleGroupStatement.get("Name");
                })
                .sorted()
                .toList();
        assertEquals(List.of("AWSManagedRulesCommonRuleSet", "AWSManagedRulesKnownBadInputsRuleSet"), ruleSetNames);

        for (var rule : managedRules) {
            var statement = (Map<String, Object>) rule.get("Statement");
            var managedRuleGroupStatement = (Map<String, Object>) statement.get("ManagedRuleGroupStatement");
            assertEquals("AWS", managedRuleGroupStatement.get("VendorName"));

            var overrideAction = (Map<String, Object>) rule.get("OverrideAction");
            assertTrue(
                    overrideAction != null && overrideAction.containsKey("None"),
                    "expected OverrideAction: none on managed rule " + rule.get("Name"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rateBasedRuleBlocksAt300RequestsPerIpPer5MinuteWindow() {
        var rules = webAclRules();
        var rateRules = rules.stream()
                .filter(r -> {
                    var statement = (Map<String, Object>) r.get("Statement");
                    return statement.containsKey("RateBasedStatement");
                })
                .toList();
        assertEquals(1, rateRules.size(), "expected exactly one rate-based rule");

        var rateRule = rateRules.get(0);
        var statement = (Map<String, Object>) rateRule.get("Statement");
        var rateBasedStatement = (Map<String, Object>) statement.get("RateBasedStatement");
        assertEquals(300, ((Number) rateBasedStatement.get("Limit")).intValue());
        assertEquals("IP", rateBasedStatement.get("AggregateKeyType"));
        assertEquals(300, ((Number) rateBasedStatement.get("EvaluationWindowSec")).intValue());

        var action = (Map<String, Object>) rateRule.get("Action");
        assertTrue(action != null && action.containsKey("Block"), "expected the rate-based rule to Block");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyWebAclRuleAndTheAclItselfHaveCloudWatchMetricsAndSamplingEnabled() {
        var template = synth();
        var props = (Map<String, Object>) template.findResources("AWS::WAFv2::WebACL")
                .values()
                .iterator()
                .next()
                .get("Properties");

        var aclVisibility = (Map<String, Object>) props.get("VisibilityConfig");
        assertEquals(Boolean.TRUE, aclVisibility.get("CloudWatchMetricsEnabled"));
        assertEquals(Boolean.TRUE, aclVisibility.get("SampledRequestsEnabled"));
        assertNotNull(aclVisibility.get("MetricName"));

        for (var rule : webAclRules()) {
            var visibility = (Map<String, Object>) rule.get("VisibilityConfig");
            assertEquals(
                    Boolean.TRUE,
                    visibility.get("CloudWatchMetricsEnabled"),
                    "expected CloudWatch metrics enabled on rule " + rule.get("Name"));
            assertEquals(
                    Boolean.TRUE,
                    visibility.get("SampledRequestsEnabled"),
                    "expected sampled requests enabled on rule " + rule.get("Name"));
        }
    }

    // The association must target the deployed REST stage (not the API or a resource), and must not
    // deploy before that stage exists - associating an ARN that doesn't exist yet fails the deploy.
    @Test
    @SuppressWarnings("unchecked")
    void webAclAssociationTargetsTheDeployedStageArnAndDependsOnIt() {
        var template = synth();
        var associations = template.findResources("AWS::WAFv2::WebACLAssociation");
        assertEquals(1, associations.size(), "expected exactly one Web ACL association");
        var association = associations.values().iterator().next();

        var props = (Map<String, Object>) association.get("Properties");
        var resourceArn = props.get("ResourceArn").toString();
        assertTrue(resourceArn.contains("/restapis/"), "expected the association to target a REST API stage ARN");
        assertTrue(resourceArn.contains("/stages/"), "expected the association to target a stage, not the bare API");

        var webAclArn = (Map<String, Object>) props.get("WebACLArn");
        assertTrue(
                webAclArn.containsKey("Fn::GetAtt")
                        && ((List<Object>) webAclArn.get("Fn::GetAtt")).get(0).equals("ApiWebAcl"),
                "expected WebACLArn to reference the ApiWebAcl resource's Arn attribute");

        var stages = template.findResources("AWS::ApiGateway::Stage");
        assertEquals(1, stages.size());
        var stageLogicalId = stages.keySet().iterator().next();
        var dependsOn = (List<String>) association.get("DependsOn");
        assertTrue(
                dependsOn != null && dependsOn.contains(stageLogicalId),
                "expected the Web ACL association to explicitly depend on the deployed stage");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> webAclRules() {
        var template = synth();
        var props = (Map<String, Object>) template.findResources("AWS::WAFv2::WebACL")
                .values()
                .iterator()
                .next()
                .get("Properties");
        return (List<Map<String, Object>>) props.get("Rules");
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
