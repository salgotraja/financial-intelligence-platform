package dev.engnotes.notifier;

import dev.engnotes.notifier.service.ConnectionRegistry;
import dev.engnotes.notifier.service.InsightFanout;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Realtime Lambda entry points (Spring Cloud Function). Two beans, one per Lambda, selected via
 * SPRING_CLOUD_FUNCTION_DEFINITION in RealtimeStack: manageConnection serves the WebSocket
 * $connect/$disconnect/subscribe routes; notifyInsight (Task 2) consumes the platform-table stream.
 *
 * <p>Events are handled as untyped maps: the aws-lambda-java-events POJOs carry Jackson-2
 * annotations that Jackson 3 ignores, which has already produced real-AWS-only defects here
 * (authorizer Condition:null, postConfirmation passthrough).
 */
@SpringBootApplication
public class NotifierHandler {

    private static final Logger log = LoggerFactory.getLogger(NotifierHandler.class);

    public static void main(String[] args) {
        SpringApplication.run(NotifierHandler.class, args);
    }

    /**
     * Handles WebSocket route events: $connect and subscribe both check deletion-pending before
     * anything else (spec s11 erasure step 1) and otherwise $connect is a no-op, subscribe
     * registers, $disconnect cleans up.
     */
    @Bean
    public Function<Map<String, Object>, Map<String, Object>> manageConnection(
            ConnectionRegistry registry, ObjectMapper mapper) {
        return event -> {
            String routeKey = null;
            String connectionId = null;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> requestContext =
                        (Map<String, Object>) event.getOrDefault("requestContext", Map.of());
                routeKey = (String) requestContext.get("routeKey");
                connectionId = (String) requestContext.get("connectionId");

                log.info("WebSocket route. routeKey={} connectionId={}", routeKey, connectionId);
                switch (routeKey == null ? "" : routeKey) {
                    case "$connect" -> {
                        // Spec s11 erasure step 1: a new connection is new personal data, refused
                        // while the caller is deletion-pending; otherwise a no-op (authorizer already
                        // validated the token, nothing to record until subscribe).
                        if (registry.isDeletionPending(authorizerSub(requestContext))) {
                            throw new IllegalStateException("deletion pending: connection refused");
                        }
                    }
                    case "$disconnect" -> registry.disconnect(connectionId);
                    case "subscribe" -> {
                        if (registry.isDeletionPending(authorizerSub(requestContext))) {
                            throw new IllegalStateException("deletion pending: subscription refused");
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> body =
                                mapper.readValue((String) event.getOrDefault("body", "{}"), Map.class);
                        @SuppressWarnings("unchecked")
                        List<String> tickers = (List<String>) body.getOrDefault("tickers", List.of());
                        registry.subscribe(connectionId, tickers);
                    }
                    default -> log.warn("Unknown routeKey ignored. routeKey={}", routeKey);
                }
                return Map.of("statusCode", 200);
            } catch (RuntimeException e) {
                log.warn(
                        "WebSocket route failed. routeKey={} connectionId={} error={}",
                        routeKey,
                        connectionId,
                        e.getMessage());
                return Map.of("statusCode", 400);
            }
        };
    }

    /** Extracts {@code sub} from the WebSocket authorizer context API Gateway attaches per connection. */
    @SuppressWarnings("unchecked")
    private static String authorizerSub(Map<String, Object> requestContext) {
        return requestContext.get("authorizer") instanceof Map<?, ?> authorizer ? (String) authorizer.get("sub") : null;
    }

    /** Consumes platform-table stream INSERTs and pushes new insights to subscribed connections. */
    @Bean
    public Function<Map<String, Object>, Map<String, Object>> notifyInsight(InsightFanout fanout) {
        return event -> {
            Map<String, Object> result = fanout.fanOut(event);
            log.info("Notify complete. delivered={} pruned={}", result.get("delivered"), result.get("pruned"));
            return result;
        };
    }
}
