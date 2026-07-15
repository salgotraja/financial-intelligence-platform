package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.query.model.MarketDataResponse;
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

@ExtendWith(MockitoExtension.class)
class MarketDataQueryTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private MarketDataQuery marketDataQuery;

    @BeforeEach
    void setUp() {
        marketDataQuery = new MarketDataQuery(dynamoDb, TABLE);
    }

    @Test
    void returnsPointsNewestFirstWhenItemsExist() {
        Map<String, AttributeValue> newest = Map.of(
                "timestamp", str("2026-07-12T10:00:00Z"),
                "price", num("2950.50"),
                "previousClose", num("2900.00"),
                "change", num("50.50"),
                "changePercent", num("1.74"),
                "volume", num("1200000"),
                "high52Week", num("3100.00"),
                "low52Week", num("2200.00"));
        Map<String, AttributeValue> older = Map.of(
                "timestamp", str("2026-07-12T09:00:00Z"),
                "price", num("2940.00"));
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(newest, older))
                        .build());

        MarketDataResponse response = marketDataQuery.findRecentPoints("RELIANCE.NS");

        assertThat(response.found()).isTrue();
        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
        assertThat(response.points()).hasSize(2);
        var first = response.points().getFirst();
        assertThat(first.timestamp()).isEqualTo("2026-07-12T10:00:00Z");
        assertThat(first.price()).isEqualByComparingTo(new BigDecimal("2950.50"));
        assertThat(first.previousClose()).isEqualByComparingTo(new BigDecimal("2900.00"));
        assertThat(first.change()).isEqualByComparingTo(new BigDecimal("50.50"));
        assertThat(first.changePercent()).isEqualByComparingTo(new BigDecimal("1.74"));
        assertThat(first.volume()).isEqualTo(1200000L);
        assertThat(first.high52Week()).isEqualByComparingTo(new BigDecimal("3100.00"));
        assertThat(first.low52Week()).isEqualByComparingTo(new BigDecimal("2200.00"));
    }

    @Test
    void toleratesMissingNumericAttributes() {
        Map<String, AttributeValue> sparse = Map.of("timestamp", str("2026-07-12T10:00:00Z"));
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(sparse))
                        .build());

        MarketDataResponse response = marketDataQuery.findRecentPoints("TCS.NS");

        assertThat(response.found()).isTrue();
        var point = response.points().getFirst();
        assertThat(point.price()).isNull();
        assertThat(point.previousClose()).isNull();
        assertThat(point.change()).isNull();
        assertThat(point.changePercent()).isNull();
        assertThat(point.volume()).isNull();
        assertThat(point.high52Week()).isNull();
        assertThat(point.low52Week()).isNull();
    }

    @Test
    void queriesTsItemsDescendingWithLimitFifty() {
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of())
                        .build());

        marketDataQuery.findRecentPoints("TCS.NS");

        ArgumentCaptor<software.amazon.awssdk.services.dynamodb.model.QueryRequest> captor =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class);
        verify(dynamoDb, times(2)).query(captor.capture());

        var tsRequest = captor.getAllValues().getFirst();
        assertThat(tsRequest.tableName()).isEqualTo(TABLE);
        assertThat(tsRequest.scanIndexForward()).isFalse();
        assertThat(tsRequest.limit()).isEqualTo(50);
        assertThat(tsRequest.keyConditionExpression()).isEqualTo("PK = :pk AND begins_with(SK, :sk)");
        assertThat(tsRequest.expressionAttributeValues()).containsValue(str("TICKER#TCS.NS"));
        assertThat(tsRequest.expressionAttributeValues()).containsValue(str("TS#"));

        var dayRequest = captor.getAllValues().getLast();
        assertThat(dayRequest.scanIndexForward()).isFalse();
        assertThat(dayRequest.limit()).isEqualTo(1);
        assertThat(dayRequest.keyConditionExpression()).isEqualTo("PK = :pk AND begins_with(SK, :sk)");
        assertThat(dayRequest.expressionAttributeValues()).containsValue(str("TICKER#TCS.NS"));
        assertThat(dayRequest.expressionAttributeValues()).containsValue(str("DAY#"));
    }

    @Test
    void returnsNotFoundWhenNoPointsExist() {
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of())
                        .build());

        MarketDataResponse response = marketDataQuery.findRecentPoints("INFY.NS");

        assertThat(response.found()).isFalse();
        assertThat(response.ticker()).isEqualTo("INFY.NS");
        assertThat(response.points()).isEmpty();
    }

    @Test
    void decodesPercentEncodedIndexTickerIntoTheDynamoKey() {
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of())
                        .build());

        MarketDataResponse response = marketDataQuery.findRecentPoints("%5ENSEI");

        assertThat(response.ticker()).isEqualTo("^NSEI");
        ArgumentCaptor<software.amazon.awssdk.services.dynamodb.model.QueryRequest> captor =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class);
        verify(dynamoDb, times(2)).query(captor.capture());
        assertThat(captor.getAllValues().getFirst().expressionAttributeValues()).containsValue(str("TICKER#^NSEI"));
    }

    @Test
    void rejectsMalformedTickerWithoutTouchingDynamoDb() {
        assertThatThrownBy(() -> marketDataQuery.findRecentPoints("bad ticker"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid ticker:");
        assertThatThrownBy(() -> marketDataQuery.findRecentPoints(null)).isInstanceOf(IllegalArgumentException.class);
        verify(dynamoDb, never()).query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class));
    }

    @Test
    void enrichesResponseWithLatestDaySeries() {
        Map<String, AttributeValue> tsPoint = Map.of(
                "timestamp", str("2026-07-13T10:00:00Z"),
                "price", num("2960.00"));
        Map<String, AttributeValue> dayItem = Map.of(
                "SK", str("DAY#2026-07-13"),
                "previousClose", num("2900.00"),
                "series",
                        AttributeValue.builder()
                                .l(seriesEntry("09:15", "2905.00"), seriesEntry("09:16", "2910.50"))
                                .build());
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(tsPoint))
                        .build())
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(dayItem))
                        .build());

        MarketDataResponse response = marketDataQuery.findRecentPoints("RELIANCE.NS");

        assertThat(response.found()).isTrue();
        assertThat(response.points()).hasSize(1);
        assertThat(response.daySeries()).hasSize(2);
        assertThat(response.daySeries().getFirst().time()).isEqualTo("09:15");
        assertThat(response.daySeries().getFirst().price()).isEqualByComparingTo(new BigDecimal("2905.00"));
        assertThat(response.daySeries().getLast().time()).isEqualTo("09:16");
        assertThat(response.daySeries().getLast().price()).isEqualByComparingTo(new BigDecimal("2910.50"));
        assertThat(response.previousClose()).isEqualByComparingTo(new BigDecimal("2900.00"));
        assertThat(response.day()).isEqualTo("2026-07-13");
    }

    @Test
    void returnsFoundWithDaySeriesWhenNoRecentPointsButDayItemExists() {
        Map<String, AttributeValue> dayItem = Map.of(
                "day", str("2026-07-13"),
                "series",
                        AttributeValue.builder()
                                .l(seriesEntry("09:15", "2905.00"))
                                .build());
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of())
                        .build())
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(dayItem))
                        .build());

        MarketDataResponse response = marketDataQuery.findRecentPoints("RELIANCE.NS");

        assertThat(response.found()).isTrue();
        assertThat(response.points()).isEmpty();
        assertThat(response.daySeries()).hasSize(1);
        assertThat(response.day()).isEqualTo("2026-07-13");
    }

    @Test
    void findLatestPointReturnsTheNewestTsItemWithLimitOneAndNoDayQuery() {
        Map<String, AttributeValue> newest = Map.of(
                "timestamp", str("2026-07-12T10:00:00Z"),
                "price", num("2950.50"));
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(newest))
                        .build());

        var latest = marketDataQuery.findLatestPoint("RELIANCE.NS");

        assertThat(latest).isPresent();
        assertThat(latest.get().timestamp()).isEqualTo("2026-07-12T10:00:00Z");
        assertThat(latest.get().price()).isEqualByComparingTo(new BigDecimal("2950.50"));

        ArgumentCaptor<software.amazon.awssdk.services.dynamodb.model.QueryRequest> captor =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class);
        verify(dynamoDb, times(1)).query(captor.capture());
        assertThat(captor.getValue().limit()).isEqualTo(1);
        assertThat(captor.getValue().expressionAttributeValues()).containsValue(str("TS#"));
    }

    @Test
    void findLatestPointReturnsEmptyWhenNoTsItemExists() {
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of())
                        .build());

        assertThat(marketDataQuery.findLatestPoint("RELIANCE.NS")).isEmpty();
    }

    @Test
    void findLatestPointRejectsMalformedTickerWithoutTouchingDynamoDb() {
        assertThatThrownBy(() -> marketDataQuery.findLatestPoint("bad ticker"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid ticker:");
        verify(dynamoDb, never()).query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class));
    }

    private static AttributeValue seriesEntry(String time, String price) {
        return AttributeValue.builder()
                .m(Map.of("t", str(time), "p", num(price)))
                .build();
    }

    private static AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue num(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
