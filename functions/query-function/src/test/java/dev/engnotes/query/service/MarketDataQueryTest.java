package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
        verify(dynamoDb).query(captor.capture());
        var sent = captor.getValue();
        assertThat(sent.tableName()).isEqualTo(TABLE);
        assertThat(sent.scanIndexForward()).isFalse();
        assertThat(sent.limit()).isEqualTo(50);
        assertThat(sent.keyConditionExpression()).isEqualTo("PK = :pk AND begins_with(SK, :sk)");
        assertThat(sent.expressionAttributeValues()).containsValue(str("TICKER#TCS.NS"));
        assertThat(sent.expressionAttributeValues()).containsValue(str("TS#"));
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
    void rejectsMalformedTickerWithoutTouchingDynamoDb() {
        assertThatThrownBy(() -> marketDataQuery.findRecentPoints("bad ticker"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid ticker:");
        assertThatThrownBy(() -> marketDataQuery.findRecentPoints(null)).isInstanceOf(IllegalArgumentException.class);
        verify(dynamoDb, never()).query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class));
    }

    private static AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue num(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
