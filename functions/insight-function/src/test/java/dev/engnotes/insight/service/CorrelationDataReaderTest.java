package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.model.TickerSeries;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

@ExtendWith(MockitoExtension.class)
class CorrelationDataReaderTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private CorrelationDataReader reader;

    @BeforeEach
    void setUp() {
        reader = new CorrelationDataReader(dynamoDb, TABLE);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static Map<String, AttributeValue> point(String timestamp, String price) {
        return Map.of("timestamp", s(timestamp), "price", n(price));
    }

    @Test
    void watchsetTickersQueriesTheUnionPartitionAndPaginates() {
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("RELIANCE.NS")), Map.of("ticker", s("TCS.NS"))))
                        .build());

        assertThat(reader.watchsetTickers()).containsExactly("RELIANCE.NS", "TCS.NS");

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).queryPaginator(captor.capture());
        assertThat(captor.getValue().expressionAttributeValues().get(":pk").s()).isEqualTo("WATCHSET");
        assertThat(captor.getValue().expressionAttributeValues().get(":sk").s()).isEqualTo("TICKER#");
    }

    @Test
    void readSeriesQueriesNewestFirstBoundedByLimit() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());

        reader.readSeries("RELIANCE.NS", 30);

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(captor.capture());
        QueryRequest request = captor.getValue();
        assertThat(request.expressionAttributeValues().get(":pk").s()).isEqualTo("TICKER#RELIANCE.NS");
        assertThat(request.expressionAttributeValues().get(":sk").s()).isEqualTo("TS#");
        assertThat(request.scanIndexForward()).isFalse();
        assertThat(request.limit()).isEqualTo(30);
    }

    @Test
    void readSeriesBucketsToTheMinuteAndReturnsAscendingChronologicalOrder() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(
                                point("2026-07-14T10:16:45Z", "102"), // newest
                                point("2026-07-14T10:15:30Z", "101"),
                                point("2026-07-14T10:14:00Z", "100"))) // oldest
                        .build());

        TickerSeries series = reader.readSeries("RELIANCE.NS", 30);

        assertThat(series.ticker()).isEqualTo("RELIANCE.NS");
        assertThat(series.buckets())
                .containsExactly("2026-07-14T10:14:00Z", "2026-07-14T10:15:00Z", "2026-07-14T10:16:00Z");
        assertThat(series.prices())
                .containsExactly(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("102"));
    }

    @Test
    void sameMinuteDuplicatesKeepTheNewestSampleOnly() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(
                                point("2026-07-14T10:15:45Z", "102"), // newest in this bucket, seen first
                                point("2026-07-14T10:15:05Z", "101"))) // same bucket, older
                        .build());

        TickerSeries series = reader.readSeries("RELIANCE.NS", 30);

        assertThat(series.buckets()).containsExactly("2026-07-14T10:15:00Z");
        assertThat(series.prices()).containsExactly(new BigDecimal("102"));
    }

    @Test
    void pointsWithNoPriceOrNonPositivePriceAreSkipped() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(
                                Map.of("timestamp", s("2026-07-14T10:16:00Z")), // no price
                                Map.of("timestamp", s("2026-07-14T10:15:00Z"), "price", n("0")), // non-positive
                                point("2026-07-14T10:14:00Z", "100")))
                        .build());

        TickerSeries series = reader.readSeries("RELIANCE.NS", 30);

        assertThat(series.buckets()).containsExactly("2026-07-14T10:14:00Z");
    }
}
