package dev.engnotes.consent;

import dev.engnotes.consent.model.ConsentRecord;
import dev.engnotes.consent.model.ConsentRequest;
import dev.engnotes.consent.model.ConsentResponse;
import dev.engnotes.consent.service.ConsentStoreService;
import dev.engnotes.consent.service.UserWatchlistPurgeService;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Consent Lambda - Spring Cloud Function entry point (DPDP, spec sub-project B).
 *
 * <p>Two beans, each selected per-Lambda via SPRING_CLOUD_FUNCTION_DEFINITION:
 *
 * <ul>
 *   <li>{@code consent} - serves GET/POST/DELETE /user/consent. The API Gateway integration template
 *       sets {@code operation} per method (POST -> GRANT, GET -> VIEW, DELETE -> WITHDRAW), the caller
 *       {@code sub} from $context.authorizer.sub (never the body), version/purpose from the body, and
 *       sourceIp/correlationId from the request context.
 *   <li>{@code postConfirmation} - the Cognito PostConfirmation trigger. Seeds a default-deny consent
 *       record + ACCOUNT_CREATED audit event at signup, then returns the event unchanged so Cognito's
 *       response contract is satisfied.
 * </ul>
 */
@SpringBootApplication
public class ConsentHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsentHandler.class);

    public static void main(String[] args) {
        SpringApplication.run(ConsentHandler.class, args);
    }

    @Bean
    public Function<ConsentRequest, ConsentResponse> consent(
            ConsentStoreService store,
            UserWatchlistPurgeService purge,
            @Value("${DEFAULT_OWNER_SUB:dev-user}") String defaultOwnerSub) {
        return request -> {
            String sub = (request.sub() != null && !request.sub().isBlank()) ? request.sub() : defaultOwnerSub;
            log.info(
                    "Consent request. operation={} sub={} correlationId={}",
                    request.operation(),
                    sub,
                    request.correlationId());

            return switch (request.operation()) {
                case GRANT ->
                    ConsentResponse.of(
                            "granted",
                            store.grant(
                                    sub,
                                    request.version(),
                                    request.purpose(),
                                    request.sourceIp(),
                                    request.correlationId()));
                case VIEW -> ConsentResponse.of("ok", store.read(sub));
                case WITHDRAW -> {
                    // Flip consent first so the watchlist gate blocks concurrent ADDs, then purge.
                    ConsentRecord record = store.withdraw(sub, request.sourceIp(), request.correlationId());
                    purge.purge(sub);
                    yield ConsentResponse.of("withdrawn", record);
                }
            };
        };
    }

    // Untyped on purpose: Cognito requires the trigger to echo the event back unchanged, and a
    // round-trip through the aws-lambda-java-events type mutates the JSON (drops the unmodeled
    // "response" object, adds nulls) because Jackson 3 ignores the library's Jackson-2 annotations.
    // Same bug class and fix as the authorizer's hand-built policy Map.
    @Bean
    public Function<Map<String, Object>, Map<String, Object>> postConfirmation(ConsentStoreService store) {
        return event -> {
            String sub = userAttribute(event, "sub");
            log.info("PostConfirmation seeding consent. sub={} userName={}", sub, event.get("userName"));
            if (sub != null && !sub.isBlank()) {
                store.seedDefaultDeny(sub);
            }
            return event;
        };
    }

    private static String userAttribute(Map<String, Object> event, String name) {
        return event.get("request") instanceof Map<?, ?> request
                        && request.get("userAttributes") instanceof Map<?, ?> attributes
                        && attributes.get(name) instanceof String value
                ? value
                : null;
    }
}
