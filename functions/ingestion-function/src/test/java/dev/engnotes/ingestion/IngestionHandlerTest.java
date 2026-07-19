package dev.engnotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.model.MarketDataRequest;
import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.service.AnomalyDetectionService;
import dev.engnotes.ingestion.service.HistoryBackfillService;
import dev.engnotes.ingestion.service.MarketDataFetchService;
import dev.engnotes.ingestion.service.MarketDataStoreService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestionHandlerTest {

    // 2026-07-13 is a Monday, 10:00 IST (04:30 UTC): inside the NSE session.
    private static final Clock OPEN_MONDAY = Clock.fixed(Instant.parse("2026-07-13T04:30:00Z"), ZoneOffset.UTC);
    // 2026-01-26 is Republic Day (an NSE holiday), 10:00 IST.
    private static final Clock HOLIDAY = Clock.fixed(Instant.parse("2026-01-26T04:30:00Z"), ZoneOffset.UTC);

    @Mock
    private MarketDataFetchService fetchService;

    @Mock
    private AnomalyDetectionService anomalyService;

    @Mock
    private MarketDataStoreService storeService;

    @Mock
    private HistoryBackfillService backfillService;

    private final IngestionHandler handler = new IngestionHandler();

    @Test
    void onDemandSourceForcesAnomalyWhenEvaluatorFoundNone() {
        Function<MarketDataRequest, MarketDataResponse> fetchMarketData =
                handler.fetchMarketData(fetchService, anomalyService, storeService, OPEN_MONDAY);

        MarketDataResponse fetched = base().build();
        MarketDataResponse evaluated = base().anomaly(false).anomalyReason(null).build();
        when(fetchService.fetch("RELIANCE.NS", "corr-1")).thenReturn(fetched);
        when(anomalyService.evaluate(fetched, "corr-1")).thenReturn(evaluated);
        when(storeService.store(any(MarketDataResponse.class), eq("corr-1")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MarketDataResponse result = fetchMarketData.apply(new MarketDataRequest("RELIANCE.NS", "corr-1", "on-demand"));

        assertThat(result.anomaly()).isTrue();
        assertThat(result.anomalyReason()).isEqualTo("on-demand refresh");

        ArgumentCaptor<MarketDataResponse> captor = ArgumentCaptor.forClass(MarketDataResponse.class);
        verify(storeService).store(captor.capture(), eq("corr-1"));
        assertThat(captor.getValue().anomaly()).isTrue();
        assertThat(captor.getValue().anomalyReason()).isEqualTo("on-demand refresh");
    }

    @Test
    void nonOnDemandSourceLeavesEvaluatorVerdictUntouched() {
        Function<MarketDataRequest, MarketDataResponse> fetchMarketData =
                handler.fetchMarketData(fetchService, anomalyService, storeService, OPEN_MONDAY);

        MarketDataResponse fetched = base().build();
        MarketDataResponse evaluated = base().anomaly(false).anomalyReason(null).build();
        when(fetchService.fetch("RELIANCE.NS", "corr-2")).thenReturn(fetched);
        when(anomalyService.evaluate(fetched, "corr-2")).thenReturn(evaluated);
        when(storeService.store(any(MarketDataResponse.class), eq("corr-2")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MarketDataResponse resultForScheduled =
                fetchMarketData.apply(new MarketDataRequest("RELIANCE.NS", "corr-2", "eventbridge-schedule"));
        assertThat(resultForScheduled.anomaly()).isFalse();
        assertThat(resultForScheduled.anomalyReason()).isNull();

        MarketDataResponse resultForNullSource =
                fetchMarketData.apply(new MarketDataRequest("RELIANCE.NS", "corr-2", null));
        assertThat(resultForNullSource.anomaly()).isFalse();
        assertThat(resultForNullSource.anomalyReason()).isNull();
    }

    @Test
    void onDemandSourceKeepsOriginalReasonWhenAlreadyAnomalous() {
        Function<MarketDataRequest, MarketDataResponse> fetchMarketData =
                handler.fetchMarketData(fetchService, anomalyService, storeService, OPEN_MONDAY);

        MarketDataResponse fetched = base().build();
        MarketDataResponse evaluated =
                base().anomaly(true).anomalyReason("return z=5.00").build();
        when(fetchService.fetch("RELIANCE.NS", "corr-3")).thenReturn(fetched);
        when(anomalyService.evaluate(fetched, "corr-3")).thenReturn(evaluated);
        when(storeService.store(any(MarketDataResponse.class), eq("corr-3")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MarketDataResponse result = fetchMarketData.apply(new MarketDataRequest("RELIANCE.NS", "corr-3", "on-demand"));

        assertThat(result.anomaly()).isTrue();
        assertThat(result.anomalyReason()).isEqualTo("return z=5.00");
    }

    @Test
    void scheduledSourceSkipsFetchWhenMarketClosedOnHoliday() {
        Function<MarketDataRequest, MarketDataResponse> fetchMarketData =
                handler.fetchMarketData(fetchService, anomalyService, storeService, HOLIDAY);

        MarketDataResponse result =
                fetchMarketData.apply(new MarketDataRequest("RELIANCE.NS", "corr-4", "eventbridge-schedule"));

        assertThat(result.stored()).isFalse();
        assertThat(result.anomaly()).isFalse();
        assertThat(result.dataSource()).isEqualTo("market-closed");
        verifyNoInteractions(fetchService, anomalyService, storeService);
    }

    @Test
    void onDemandSourceFetchesEvenWhenMarketClosedOnHoliday() {
        Function<MarketDataRequest, MarketDataResponse> fetchMarketData =
                handler.fetchMarketData(fetchService, anomalyService, storeService, HOLIDAY);

        MarketDataResponse fetched = base().build();
        MarketDataResponse evaluated = base().anomaly(false).anomalyReason(null).build();
        when(fetchService.fetch("RELIANCE.NS", "corr-5")).thenReturn(fetched);
        when(anomalyService.evaluate(fetched, "corr-5")).thenReturn(evaluated);
        when(storeService.store(any(MarketDataResponse.class), eq("corr-5")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MarketDataResponse result = fetchMarketData.apply(new MarketDataRequest("RELIANCE.NS", "corr-5", "on-demand"));

        assertThat(result.anomaly()).isTrue();
        assertThat(result.anomalyReason()).isEqualTo("on-demand refresh");
        verify(storeService).store(any(MarketDataResponse.class), eq("corr-5"));
    }

    @Test
    void backfillBeanProcessesStreamRecordsAndAggregates() {
        when(backfillService.backfill("INFY.NS", "stream-backfill"))
                .thenReturn(new HistoryBackfillService.BackfillResult("INFY.NS", 240, 5));
        var bean = handler.backfillDailyHistory(backfillService);

        Map<String, Object> event = Map.of(
                "Records",
                List.of(Map.of(
                        "eventName",
                        "INSERT",
                        "dynamodb",
                        Map.of(
                                "NewImage",
                                Map.of(
                                        "PK", Map.of("S", "WATCHSET"),
                                        "SK", Map.of("S", "TICKER#INFY.NS"),
                                        "ticker", Map.of("S", "INFY.NS"))))));

        Map<String, Object> summary = bean.apply(event);

        assertThat(summary)
                .containsEntry("processed", 1)
                .containsEntry("written", 240)
                .containsEntry("skipped", 5);
    }

    @Test
    void backfillBeanSkipsMalformedRecordsButRethrowsOnPerTickerFailure() {
        when(backfillService.backfill("TCS.NS", "stream-backfill")).thenThrow(new RuntimeException("yahoo 429"));
        var bean = handler.backfillDailyHistory(backfillService);

        Map<String, Object> event = Map.of(
                "Records",
                List.of(
                        Map.of("eventName", "INSERT"), // no dynamodb block: skipped silently
                        Map.of(
                                "eventName",
                                "INSERT",
                                "dynamodb",
                                Map.of("NewImage", Map.of("ticker", Map.of("S", "TCS.NS"))))));

        assertThatThrownBy(() -> bean.apply(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("TCS.NS");
    }

    @Test
    void backfillBeanAttemptsAllTickersThenRethrowsWithFailedTickerAfterSuccessfulWrite() {
        when(backfillService.backfill("INFY.NS", "stream-backfill"))
                .thenReturn(new HistoryBackfillService.BackfillResult("INFY.NS", 240, 5));
        when(backfillService.backfill("TCS.NS", "stream-backfill")).thenThrow(new RuntimeException("yahoo 429"));
        var bean = handler.backfillDailyHistory(backfillService);

        Map<String, Object> event =
                Map.of("Records", List.of(watchsetInsertRecord("INFY.NS"), watchsetInsertRecord("TCS.NS")));

        assertThatThrownBy(() -> bean.apply(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("TCS.NS");

        verify(backfillService).backfill("INFY.NS", "stream-backfill");
        verify(backfillService).backfill("TCS.NS", "stream-backfill");
    }

    private static Map<String, Object> watchsetInsertRecord(String ticker) {
        return Map.of(
                "eventName",
                "INSERT",
                "dynamodb",
                Map.of(
                        "NewImage",
                        Map.of(
                                "PK", Map.of("S", "WATCHSET"),
                                "SK", Map.of("S", "TICKER#" + ticker),
                                "ticker", Map.of("S", ticker))));
    }

    private static MarketDataResponse.Builder base() {
        return MarketDataResponse.builder()
                .ticker("RELIANCE.NS")
                .dataSource("yahoo-finance")
                .price(new BigDecimal("1400.00"));
    }
}
