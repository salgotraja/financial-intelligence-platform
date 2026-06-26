package dev.engnotes.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.engnotes.query.model.QueryRequest;
import dev.engnotes.query.model.QueryResponse;
import dev.engnotes.query.service.InsightQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryHandlerTest {

    @Mock
    private InsightQuery insightQuery;

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
}
