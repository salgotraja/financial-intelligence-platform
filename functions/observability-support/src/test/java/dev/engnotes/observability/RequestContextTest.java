package dev.engnotes.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class RequestContextTest {

    @Test
    void beginPopulatesMdcAndClearOnClose() {
        try (RequestContext ctx = RequestContext.begin("query-fn", "req-123")) {
            assertThat(MDC.get("function")).isEqualTo("query-fn");
            assertThat(MDC.get("correlationId")).isEqualTo("req-123");
            assertThat(ctx.correlationId()).isEqualTo("req-123");
        }
        assertThat(MDC.get("function")).isNull();
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void generatesCorrelationIdWhenBlank() {
        try (RequestContext ctx = RequestContext.begin("query-fn", "  ")) {
            assertThat(ctx.correlationId()).isNotBlank();
            assertThat(MDC.get("correlationId")).isEqualTo(ctx.correlationId());
        }
    }

    @Test
    void withTickerAndUserSetMdc() {
        try (RequestContext ctx = RequestContext.begin("query-fn", "r")) {
            ctx.withTicker("RELIANCE.NS").withUser("user-9");
            assertThat(MDC.get("ticker")).isEqualTo("RELIANCE.NS");
            assertThat(MDC.get("userId")).isEqualTo("user-9");
        }
        assertThat(MDC.get("ticker")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void parseTraceRootExtractsRootSegment() {
        String header = "Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8;Sampled=1";
        assertThat(RequestContext.parseTraceRoot(header)).isEqualTo("1-5759e988-bd862e3fe1be46a994272793");
    }

    @Test
    void parseTraceRootReturnsNullWhenAbsent() {
        assertThat(RequestContext.parseTraceRoot(null)).isNull();
        assertThat(RequestContext.parseTraceRoot("Parent=abc;Sampled=1")).isNull();
    }
}
