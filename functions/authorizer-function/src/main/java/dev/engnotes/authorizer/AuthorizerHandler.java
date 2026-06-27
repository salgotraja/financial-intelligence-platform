package dev.engnotes.authorizer;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.amazonaws.services.lambda.runtime.events.IamPolicyResponse;
import com.amazonaws.services.lambda.runtime.events.IamPolicyResponse.Statement;
import dev.engnotes.authorizer.jwt.JwtVerifier;
import dev.engnotes.authorizer.jwt.Principal;
import dev.engnotes.authorizer.policy.RoutePolicy;
import java.util.Arrays;
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

    public static void main(String[] args) {
        SpringApplication.run(AuthorizerHandler.class, args);
    }

    @Bean
    public Function<APIGatewayCustomAuthorizerEvent, IamPolicyResponse> authorize(JwtVerifier verifier) {
        return event -> {
            String methodArn = event.getMethodArn();
            try {
                Principal principal = verifier.verify(stripBearer(event.getAuthorizationToken()));
                List<RoutePolicy.Rule> allowed = RoutePolicy.allowedRules(principal.groups());
                if (allowed.isEmpty() || !requestedRouteAllowed(methodArn, allowed)) {
                    log.info("Deny (no permitted routes). sub={}", principal.sub());
                    return policy(principal.sub(), deny(methodArn), principal.sub());
                }
                String base = arnBase(methodArn);
                List<String> resources = allowed.stream()
                        .map(rule -> base + "/" + rule.httpMethod() + "/" + rule.resourcePattern())
                        .toList();
                return policy(principal.sub(), allow(resources), principal.sub());
            } catch (RuntimeException e) {
                log.warn("Deny (verification failed): {}", e.getMessage());
                return policy("unknown", deny(methodArn), "unknown");
            }
        };
    }

    /**
     * Returns true if the specific route embedded in the method-ARN is covered by at least one
     * allowed rule. The ARN path after {apiId}/{stage} is {HTTP_METHOD}/{resource/path...}, so
     * pathParts[2] is the verb and pathParts[3..] is the resource path.
     */
    private static boolean requestedRouteAllowed(String methodArn, List<RoutePolicy.Rule> allowed) {
        String[] colon = methodArn.split(":", 6);
        String[] pathParts = colon[5].split("/");
        String requestedMethod = pathParts[2];
        String requestedPath = String.join("/", Arrays.copyOfRange(pathParts, 3, pathParts.length));
        return allowed.stream()
                .anyMatch(rule -> rule.httpMethod().equals(requestedMethod)
                        && requestedPath.matches(rule.resourcePattern().replace("*", ".*")));
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

    private static Statement allow(List<String> resources) {
        return Statement.builder()
                .withEffect("Allow")
                .withAction("execute-api:Invoke")
                .withResource(resources)
                .build();
    }

    private static Statement deny(String resource) {
        return Statement.builder()
                .withEffect("Deny")
                .withAction("execute-api:Invoke")
                .withResource(List.of(resource))
                .build();
    }

    private static IamPolicyResponse policy(String principalId, Statement statement, String sub) {
        IamPolicyResponse.PolicyDocument document = IamPolicyResponse.PolicyDocument.builder()
                .withVersion(IamPolicyResponse.VERSION_2012_10_17)
                .withStatement(List.of(statement))
                .build();
        return IamPolicyResponse.builder()
                .withPrincipalId(principalId)
                .withPolicyDocument(document)
                .withContext(Map.of("sub", sub))
                .build();
    }
}
