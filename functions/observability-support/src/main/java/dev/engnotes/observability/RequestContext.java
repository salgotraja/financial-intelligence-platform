package dev.engnotes.observability;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * Populates the SLF4J MDC for one Lambda invocation so every log line carries a correlation ID, the
 * X-Ray trace ID (for log-to-trace linking), the function name, and optional business context. Use
 * with try-with-resources; {@link #close()} removes exactly the keys it set, which matters because
 * Lambda reuses execution environments (and their MDC) across invocations.
 */
public final class RequestContext implements AutoCloseable {

    private static final String TRACE_ENV = "_X_AMZN_TRACE_ID";

    private final String correlationId;

    private RequestContext(String function, String correlationId) {
        this.correlationId = correlationId;
        MDC.put("function", function);
        MDC.put("correlationId", correlationId);
        String traceId = parseTraceRoot(System.getenv(TRACE_ENV));
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
    }

    public static RequestContext begin(String function, String correlationId) {
        String resolved = (correlationId == null || correlationId.isBlank())
                ? UUID.randomUUID().toString()
                : correlationId;
        return new RequestContext(function, resolved);
    }

    public RequestContext withTicker(String ticker) {
        if (ticker != null) {
            MDC.put("ticker", ticker);
        }
        return this;
    }

    public RequestContext withUser(String userId) {
        if (userId != null) {
            MDC.put("userId", userId);
        }
        return this;
    }

    public String correlationId() {
        return correlationId;
    }

    public static String parseTraceRoot(String header) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("Root=")) {
                return trimmed.substring("Root=".length());
            }
        }
        return null;
    }

    @Override
    public void close() {
        MDC.remove("function");
        MDC.remove("correlationId");
        MDC.remove("traceId");
        MDC.remove("ticker");
        MDC.remove("userId");
    }
}
