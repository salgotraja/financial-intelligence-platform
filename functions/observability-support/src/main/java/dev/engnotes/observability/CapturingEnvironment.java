package dev.engnotes.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import software.amazon.cloudwatchlogs.emf.environment.Environment;
import software.amazon.cloudwatchlogs.emf.logger.MetricsLogger;
import software.amazon.cloudwatchlogs.emf.model.MetricsContext;
import software.amazon.cloudwatchlogs.emf.sinks.ISink;

/**
 * Test-only {@link Environment} that captures the {@link MetricsContext} passed to its sink
 * instead of publishing it anywhere. Shared by {@code MetricsTest} (single-record inspection) and
 * {@link Metrics#forTesting()} (accumulating capture across many emissions).
 */
final class CapturingEnvironment implements Environment {

    private final List<String> sink;
    private MetricsContext captured;

    CapturingEnvironment() {
        this(null);
    }

    CapturingEnvironment(List<String> sink) {
        this.sink = sink;
    }

    /** Builds a {@link MetricsLogger} whose flushed records are appended to {@code sink}. */
    static MetricsLogger newCapturingLogger(List<String> sink) {
        return new MetricsLogger(new CapturingEnvironment(sink));
    }

    @Override
    public boolean probe() {
        return true;
    }

    @Override
    public String getName() {
        return "test-service";
    }

    @Override
    public String getType() {
        return "test-type";
    }

    @Override
    public String getLogGroupName() {
        return "test-log-group";
    }

    @Override
    public void configureContext(MetricsContext context) {}

    @Override
    public ISink getSink() {
        return new ISink() {
            @Override
            public void accept(MetricsContext context) {
                captured = context;
                if (sink != null) {
                    try {
                        sink.addAll(context.serialize());
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Failed to serialize captured metrics context", e);
                    }
                }
            }

            @Override
            public CompletableFuture<Void> shutdown() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    String serializedRecord() throws Exception {
        return String.join("\n", captured.serialize());
    }
}
