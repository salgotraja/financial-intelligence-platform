package dev.engnotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.model.MarketDataRequest;
import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.service.AnomalyDetectionService;
import dev.engnotes.ingestion.service.MarketDataFetchService;
import dev.engnotes.ingestion.service.MarketDataStoreService;
import java.math.BigDecimal;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestionHandlerTest {

    @Mock
    private MarketDataFetchService fetchService;

    @Mock
    private AnomalyDetectionService anomalyService;

    @Mock
    private MarketDataStoreService storeService;

    private final IngestionHandler handler = new IngestionHandler();

    @Test
    void onDemandSourceForcesAnomalyWhenEvaluatorFoundNone() {
        Function<MarketDataRequest, MarketDataResponse> fetchMarketData =
                handler.fetchMarketData(fetchService, anomalyService, storeService);

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
                handler.fetchMarketData(fetchService, anomalyService, storeService);

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
                handler.fetchMarketData(fetchService, anomalyService, storeService);

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

    private static MarketDataResponse.Builder base() {
        return MarketDataResponse.builder()
                .ticker("RELIANCE.NS")
                .dataSource("yahoo-finance")
                .price(new BigDecimal("1400.00"));
    }
}
