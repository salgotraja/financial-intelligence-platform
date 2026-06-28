package dev.engnotes.authorizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.amazonaws.services.lambda.runtime.events.IamPolicyResponse;
import dev.engnotes.authorizer.jwt.JwtVerifier;
import dev.engnotes.authorizer.jwt.Principal;
import java.util.Arrays;
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

    private APIGatewayCustomAuthorizerEvent event() {
        APIGatewayCustomAuthorizerEvent event = new APIGatewayCustomAuthorizerEvent();
        event.setType("TOKEN");
        event.setAuthorizationToken("Bearer some.jwt.token");
        event.setMethodArn(METHOD_ARN);
        return event;
    }

    // getPolicyDocument() serialises statements as Map[] (one Map per Statement, with String[] Resource).
    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstStatement(IamPolicyResponse response) {
        Map<String, Object>[] stmts =
                (Map<String, Object>[]) response.getPolicyDocument().get("Statement");
        return stmts[0];
    }

    private static List<String> resourceList(Map<String, Object> statement) {
        return Arrays.asList((String[]) statement.get("Resource"));
    }

    @Test
    void premiumGetsAllowPolicyWithSubContext() {
        when(verifier.verify("some.jwt.token")).thenReturn(new Principal("user-abc", List.of("premium")));

        IamPolicyResponse response = new AuthorizerHandler().authorize(verifier).apply(event());

        assertThat(response.getPrincipalId()).isEqualTo("user-abc");
        assertThat(response.getContext()).containsEntry("sub", "user-abc");
        assertThat(response.getContext()).containsEntry("groups", "premium");
        Map<String, Object> statement = firstStatement(response);
        assertThat(statement.get("Effect")).isEqualTo("Allow");
        assertThat(Arrays.asList((String[]) statement.get("Resource"))).anyMatch(r -> r.contains("/POST/watchlist/*"));
    }

    @Test
    void readerPolicyAllowsReadsButExcludesWatchlist() {
        when(verifier.verify("some.jwt.token")).thenReturn(new Principal("user-reader", List.of("readers")));

        IamPolicyResponse response = new AuthorizerHandler().authorize(verifier).apply(event());

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

        IamPolicyResponse response = new AuthorizerHandler().authorize(verifier).apply(event());

        assertThat(firstStatement(response).get("Effect")).isEqualTo("Deny");
    }
}
