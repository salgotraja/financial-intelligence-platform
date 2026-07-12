package dev.engnotes.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.engnotes.query.model.MarketDataPoint;
import dev.engnotes.query.model.MarketDataResponse;
import dev.engnotes.query.model.QueryRequest;
import dev.engnotes.query.model.QueryResponse;
import dev.engnotes.query.service.InsightQuery;
import dev.engnotes.query.service.MarketDataQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryHandlerTest {

    @Mock
    private InsightQuery insightQuery;

    @Mock
    private MarketDataQuery marketDataQuery;

    @Test
    void serveInsightDelegatesToInsightQuery() {
        QueryResponse expected = new QueryResponse(
                "RELIANCE.NS",
                "2026-06-26T10:00:00Z",
                "BULLISH",
                0.82,
                "Positive.",
                List.of("change percent"),
                "BEDROCK",
                "Positive.",
                "model-x",
                true);
        when(insightQuery.findLatestInsight("RELIANCE.NS")).thenReturn(expected);

        QueryResponse actual =
                new QueryHandler().serveInsight(insightQuery).apply(new QueryRequest("RELIANCE.NS", "corr-1"));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void serveMarketDataDelegatesToMarketDataQuery() {
        var expected = new MarketDataResponse(
                "RELIANCE.NS",
                List.of(new MarketDataPoint("2026-07-12T10:00:00Z", null, null, null, null, null, null, null)),
                true);
        when(marketDataQuery.findRecentPoints("RELIANCE.NS")).thenReturn(expected);

        var actual =
                new QueryHandler().serveMarketData(marketDataQuery).apply(new QueryRequest("RELIANCE.NS", "corr-2"));

        assertThat(actual).isEqualTo(expected);
    }
}
