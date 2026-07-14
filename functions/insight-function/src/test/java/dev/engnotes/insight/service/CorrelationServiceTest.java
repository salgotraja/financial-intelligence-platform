package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.model.CorrelationGroup;
import dev.engnotes.insight.model.CorrelationResponse;
import dev.engnotes.insight.model.TickerSeries;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CorrelationServiceTest {

    @Mock
    private CorrelationDataReader dataReader;

    @Mock
    private CorrelationStoreService storeService;

    private CorrelationService service;

    @BeforeEach
    void setUp() {
        service = new CorrelationService(dataReader, storeService, 0.6);
    }

    /**
     * 10 ascending buckets, price = scale * (100 + i^2). A percentage-return series is scale-invariant
     * (multiplying every price by a constant leaves (p[i]-p[i-1])/p[i-1] unchanged), so two series built
     * from this helper with different scales are perfectly correlated in returns space even though their
     * price levels differ - unlike an additive offset, which is not scale-invariant.
     */
    private static TickerSeries series(String ticker, double scale) {
        List<String> buckets = IntStream.rangeClosed(1, 10)
                .mapToObj("2026-07-14T10:%02d:00Z"::formatted)
                .toList();
        List<BigDecimal> prices = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> BigDecimal.valueOf(scale * (100 + (double) i * i)))
                .toList();
        return new TickerSeries(ticker, buckets, prices);
    }

    private static TickerSeries tooShortSeries(String ticker) {
        return new TickerSeries(ticker, List.of("2026-07-14T10:01:00Z"), List.of(new BigDecimal("100")));
    }

    @Test
    void tickerWithFewerThanMinAlignedPointsIsExcludedFromEveryPair() {
        when(dataReader.watchsetTickers()).thenReturn(List.of("A", "B"));
        when(dataReader.readSeries("A", CorrelationService.WINDOW_SIZE)).thenReturn(series("A", 1.0));
        when(dataReader.readSeries("B", CorrelationService.WINDOW_SIZE)).thenReturn(tooShortSeries("B"));

        CorrelationResponse response = service.compute(Instant.parse("2026-07-14T10:15:00Z"));

        assertThat(response.tickersEvaluated()).isEqualTo(1); // only A qualified
        assertThat(response.groupsComputed()).isZero();
        ArgumentCaptor<List<CorrelationGroup>> captor = ArgumentCaptor.forClass(List.class);
        verify(storeService).replaceAll(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void perfectlyCorrelatedPairAboveThresholdFormsAPersistedGroup() {
        when(dataReader.watchsetTickers()).thenReturn(List.of("A", "B"));
        when(dataReader.readSeries("A", CorrelationService.WINDOW_SIZE)).thenReturn(series("A", 1.0));
        when(dataReader.readSeries("B", CorrelationService.WINDOW_SIZE)).thenReturn(series("B", 2.0));

        CorrelationResponse response = service.compute(Instant.parse("2026-07-14T10:15:00Z"));

        assertThat(response.tickersEvaluated()).isEqualTo(2);
        assertThat(response.groupsComputed()).isEqualTo(1);
        assertThat(response.computedAt()).isEqualTo("2026-07-14T10:15:00Z");

        ArgumentCaptor<List<CorrelationGroup>> captor = ArgumentCaptor.forClass(List.class);
        verify(storeService).replaceAll(captor.capture());
        CorrelationGroup group = captor.getValue().getFirst();
        assertThat(group.members()).containsExactly("A", "B");
        assertThat(group.groupId()).isEqualTo(GroupIdGenerator.groupId(List.of("A", "B")));
        assertThat(group.pairwiseRhos()).hasSize(1);
        assertThat(group.pairwiseRhos().getFirst().rho()).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void unrelatedTickersProduceNoGroup() {
        when(dataReader.watchsetTickers()).thenReturn(List.of("A", "B"));
        TickerSeries a = series("A", 1.0);
        // B alternates so its returns are uncorrelated with A's steadily-rising series.
        List<String> buckets = a.buckets();
        List<BigDecimal> alternating = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> BigDecimal.valueOf(i % 2 == 0 ? 60.0 : 40.0))
                .toList();
        when(dataReader.readSeries("A", CorrelationService.WINDOW_SIZE)).thenReturn(a);
        when(dataReader.readSeries("B", CorrelationService.WINDOW_SIZE))
                .thenReturn(new TickerSeries("B", buckets, alternating));

        CorrelationResponse response = service.compute(Instant.parse("2026-07-14T10:15:00Z"));

        assertThat(response.groupsComputed()).isZero();
    }

    @Test
    void emptyWatchsetProducesNoGroupsAndStillCallsStore() {
        when(dataReader.watchsetTickers()).thenReturn(List.of());

        CorrelationResponse response = service.compute(Instant.parse("2026-07-14T10:15:00Z"));

        assertThat(response.tickersEvaluated()).isZero();
        assertThat(response.groupsComputed()).isZero();
        verify(storeService).replaceAll(List.of());
    }
}
