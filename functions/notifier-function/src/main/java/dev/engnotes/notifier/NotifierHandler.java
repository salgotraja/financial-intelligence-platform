package dev.engnotes.notifier;

import dev.engnotes.notifier.service.ConnectionRegistry;
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

    /** Handles WebSocket route events: $connect no-op, subscribe registers, $disconnect cleans up. */
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
                        // Authorizer already validated the token; nothing to record until subscribe.
                    }
                    case "$disconnect" -> registry.disconnect(connectionId);
                    case "subscribe" -> {
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
}
