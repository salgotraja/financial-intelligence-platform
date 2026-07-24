package dev.engnotes.authorizer;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import dev.engnotes.authorizer.jwt.JwtVerifier;
import dev.engnotes.authorizer.jwt.Principal;
import dev.engnotes.authorizer.policy.RoutePolicy;
import dev.engnotes.observability.Metrics;
import dev.engnotes.observability.RequestContext;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * API Gateway TOKEN authorizer (spec section 11). Verifies the Cognito access token, evaluates the
 * caller's {@code cognito:groups} against {@link RoutePolicy}, and returns an IAM Allow policy
 * covering every route the groups permit (so the cached decision is reusable across the caller's
 * routes) plus the {@code sub} in the authorizer context. Fails closed: any verification failure or
 * a caller with no permitted routes yields a Deny. {@code /health} is left unauthenticated in the
 * API, so it never reaches here.
 *
 * <p>The bean name ("authorize") must match SPRING_CLOUD_FUNCTION_DEFINITION in QueryStack.
 */
@SpringBootApplication
public class AuthorizerHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthorizerHandler.class);

    static final String MODULE_LABEL = "financial-authorizer";

    public static void main(String[] args) {
        SpringApplication.run(AuthorizerHandler.class, args);
    }

    @Bean
    public Metrics metrics() {
        return Metrics.forFunction(MODULE_LABEL);
    }

    @Bean
    public Function<APIGatewayCustomAuthorizerEvent, Map<String, Object>> authorize(
            JwtVerifier verifier, Metrics metrics) {
        return event -> {
            try (var ctx = RequestContext.begin(MODULE_LABEL, null)) {
                String methodArn = event.getMethodArn();
                try {
                    Principal principal = verifier.verify(stripBearer(event.getAuthorizationToken()));
                    List<RoutePolicy.Rule> allowed = RoutePolicy.allowedRules(principal.groups());
                    if (allowed.isEmpty()) {
                        log.info("Deny (no permitted routes). sub={}", principal.sub());
                        metrics.count("AuthDenied", "reason", "no_permitted_routes");
                        return policy(
                                principal.sub(), deny(methodArn), principal.sub(), joinGroups(principal.groups()));
                    }
                    String base = arnBase(methodArn);
                    List<String> resources = allowed.stream()
                            .map(rule -> base + "/" + rule.httpMethod() + "/" + rule.resourcePattern())
                            .toList();
                    return policy(principal.sub(), allow(resources), principal.sub(), joinGroups(principal.groups()));
                } catch (RuntimeException e) {
                    log.warn("Deny (verification failed): {}", e.getMessage());
                    metrics.count("AuthDenied", "reason", "verification_failed");
                    return policy("unknown", deny(methodArn), "unknown", "");
                }
            }
        };
    }

    private static String stripBearer(String token) {
        if (token == null) {
            throw new IllegalStateException("missing Authorization token");
        }
        return token.regionMatches(true, 0, "Bearer ", 0, 7) ? token.substring(7) : token;
    }

    /** Base method-ARN up to the stage: arn:aws:execute-api:region:acct:apiId/stage. */
    private static String arnBase(String methodArn) {
        String[] colon = methodArn.split(":", 6);
        String[] path = colon[5].split("/");
        return String.join(":", colon[0], colon[1], colon[2], colon[3], colon[4], path[0]) + "/" + path[1];
    }

    // API Gateway rejects a policy statement that carries a null Condition; build statements with
    // no Condition key. (Jackson 3 ignores the aws-lambda-java-events Jackson-2 @JsonInclude that
    // would otherwise have dropped it.)
    private static Map<String, Object> allow(List<String> resources) {
        return statement("Allow", resources);
    }

    private static Map<String, Object> deny(String resource) {
        return statement("Deny", List.of(resource));
    }

    private static Map<String, Object> statement(String effect, List<String> resources) {
        return Map.of("Effect", effect, "Action", "execute-api:Invoke", "Resource", resources);
    }

    private static Map<String, Object> policy(
            String principalId, Map<String, Object> statement, String sub, String groups) {
        return Map.of(
                "principalId", principalId,
                "policyDocument", Map.of("Version", "2012-10-17", "Statement", List.of(statement)),
                "context", Map.of("sub", sub, "groups", groups));
    }

    private static String joinGroups(List<String> groups) {
        return groups == null ? "" : String.join(",", groups);
    }
}
