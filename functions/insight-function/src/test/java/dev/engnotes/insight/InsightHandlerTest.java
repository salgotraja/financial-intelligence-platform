package dev.engnotes.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.model.CorrelationRequest;
import dev.engnotes.insight.model.CorrelationResponse;
import dev.engnotes.insight.service.CorrelationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Covers only the computeCorrelations bean's market-hours guard; generateInsight is untested at the handler level. */
@ExtendWith(MockitoExtension.class)
class InsightHandlerTest {

    // 2026-07-13 is a Monday, 10:00 IST (04:30 UTC): inside the NSE session.
    private static final Clock OPEN_MONDAY = Clock.fixed(Instant.parse("2026-07-13T04:30:00Z"), ZoneOffset.UTC);
    // 2026-01-26 is Republic Day (an NSE holiday), 10:00 IST.
    private static final Clock HOLIDAY = Clock.fixed(Instant.parse("2026-01-26T04:30:00Z"), ZoneOffset.UTC);

    @Mock
    private CorrelationService correlationService;

    private final InsightHandler handler = new InsightHandler();

    @Test
    void marketClosedSkipsComputationAndReturnsMarketClosedStatus() {
        Function<CorrelationRequest, CorrelationResponse> computeCorrelations =
                handler.computeCorrelations(correlationService, HOLIDAY);

        CorrelationResponse response = computeCorrelations.apply(new CorrelationRequest("eventbridge-schedule"));

        assertThat(response.status()).isEqualTo("market-closed");
        assertThat(response.tickersEvaluated()).isZero();
        assertThat(response.groupsComputed()).isZero();
        assertThat(response.computedAt()).isNull();
        verifyNoInteractions(correlationService);
    }

    @Test
    void marketOpenDelegatesToTheCorrelationService() {
        Function<CorrelationRequest, CorrelationResponse> computeCorrelations =
                handler.computeCorrelations(correlationService, OPEN_MONDAY);
        CorrelationResponse computed = new CorrelationResponse("computed", 3, 1, "2026-07-13T04:30:00Z");
        when(correlationService.compute(eq(Instant.parse("2026-07-13T04:30:00Z"))))
                .thenReturn(computed);

        CorrelationResponse response = computeCorrelations.apply(new CorrelationRequest("eventbridge-schedule"));

        assertThat(response).isEqualTo(computed);
        verify(correlationService).compute(Instant.parse("2026-07-13T04:30:00Z"));
    }
}
