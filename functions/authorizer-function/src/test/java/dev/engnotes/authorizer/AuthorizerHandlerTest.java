package dev.engnotes.authorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import dev.engnotes.authorizer.jwt.JwtVerifier;
import dev.engnotes.authorizer.jwt.Principal;
import dev.engnotes.observability.Metrics;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthorizerHandlerTest {

    private static final String METHOD_ARN =
            "arn:aws:execute-api:ap-south-1:123456789012:abc123/dev/POST/watchlist/RELIANCE.NS";

    @Mock
    private JwtVerifier verifier;

    private final Metrics.Capture capture = Metrics.forTesting();

    private APIGatewayCustomAuthorizerEvent event() {
        APIGatewayCustomAuthorizerEvent event = new APIGatewayCustomAuthorizerEvent();
        event.setType("TOKEN");
        event.setAuthorizationToken("Bearer some.jwt.token");
        event.setMethodArn(METHOD_ARN);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstStatement(Map<String, Object> response) {
        Map<String, Object> doc = (Map<String, Object>) response.get("policyDocument");
        List<Map<String, Object>> stmts = (List<Map<String, Object>>) doc.get("Statement");
        return stmts.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> resourceList(Map<String, Object> statement) {
        return (List<String>) statement.get("Resource");
    }

    @Test
    void premiumGetsAllowPolicyWithSubContext() {
        when(verifier.verify("some.jwt.token")).thenReturn(new Principal("user-abc", List.of("premium")));

        Map<String, Object> response =
                new AuthorizerHandler().authorize(verifier, capture.metrics()).apply(event());

        assertThat(response.get("principalId")).isEqualTo("user-abc");
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) response.get("context");
        assertThat(ctx).containsEntry("sub", "user-abc");
        assertThat(ctx).containsEntry("groups", "premium");
        Map<String, Object> statement = firstStatement(response);
        assertThat(statement.get("Effect")).isEqualTo("Allow");
        assertThat(resourceList(statement)).anyMatch(r -> r.contains("/POST/watchlist/*"));
    }

    @Test
    void readerPolicyAllowsReadsButExcludesWatchlist() {
        when(verifier.verify("some.jwt.token")).thenReturn(new Principal("user-reader", List.of("readers")));

        Map<String, Object> response =
                new AuthorizerHandler().authorize(verifier, capture.metrics()).apply(event());

        // Cache-safe: policy is route-independent. Reader gets an Allow listing only read resources;
        // API Gateway then denies the POST /watchlist methodArn because it is not in the resource list.
        Map<String, Object> statement = firstStatement(response);
        assertThat(statement.get("Effect")).isEqualTo("Allow");
        List<String> resources = resourceList(statement);
        assertThat(resources).anyMatch(r -> r.contains("/GET/insights/*"));
        assertThat(resources).noneMatch(r -> r.contains("watchlist"));
    }

    @Test
    void invalidTokenGetsDeny() {
        when(verifier.verify("some.jwt.token")).thenThrow(new IllegalStateException("bad token"));

        Map<String, Object> response =
                new AuthorizerHandler().authorize(verifier, capture.metrics()).apply(event());

        assertThat(firstStatement(response).get("Effect")).isEqualTo("Deny");
    }

    @Test
    void allowStatementHasNoConditionKey() { // regression: null Condition broke API Gateway policy parsing
        when(verifier.verify("some.jwt.token")).thenReturn(new Principal("u", List.of("readers")));
        Map<String, Object> response =
                new AuthorizerHandler().authorize(verifier, capture.metrics()).apply(event());
        assertThat(firstStatement(response)).doesNotContainKey("Condition");
    }

    @Test
    void noPermittedRoutesEmitsAuthDeniedMetric() {
        when(verifier.verify("some.jwt.token")).thenReturn(new Principal("user-none", List.of()));

        new AuthorizerHandler().authorize(verifier, capture.metrics()).apply(event());

        assertThat(capture.records())
                .anySatisfy(record ->
                        assertThat(record).contains("\"AuthDenied\"").contains("\"reason\":\"no_permitted_routes\""));
    }

    @Test
    void verificationFailureEmitsAuthDeniedMetric() {
        when(verifier.verify("some.jwt.token")).thenThrow(new IllegalStateException("bad token"));

        new AuthorizerHandler().authorize(verifier, capture.metrics()).apply(event());

        assertThat(capture.records())
                .anySatisfy(record ->
                        assertThat(record).contains("\"AuthDenied\"").contains("\"reason\":\"verification_failed\""));
    }
}
