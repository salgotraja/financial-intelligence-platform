package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.query.model.DailyMarketDataResponse;
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
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

@ExtendWith(MockitoExtension.class)
class DailyMarketDataQueryTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private DailyMarketDataQuery dailyMarketDataQuery;

    @BeforeEach
    void setUp() {
        dailyMarketDataQuery = new DailyMarketDataQuery(dynamoDb, TABLE);
    }

    @Test
    void mapsDayItemsNewestFirstWithoutTheSeriesBlob() {
        Map<String, AttributeValue> day1 = Map.of(
                "day", str("2026-07-14"),
                "open", num("2900.00"),
                "high", num("2950.00"),
                "low", num("2890.00"),
                "close", num("2940.00"),
                "previousClose", num("2895.00"),
                "volume", num("1200000"),
                "series", AttributeValue.builder().l(List.of()).build());
        Map<String, AttributeValue> day2 = Map.of("day", str("2026-07-13"), "close", num("2895.00"));
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of(day1, day2)).build());

        DailyMarketDataResponse response = dailyMarketDataQuery.findDailyPoints("RELIANCE.NS", "30");

        assertThat(response.found()).isTrue();
        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
        assertThat(response.days()).hasSize(2);
        var first = response.days().getFirst();
        assertThat(first.date()).isEqualTo("2026-07-14");
        assertThat(first.open()).isEqualByComparingTo(new BigDecimal("2900.00"));
        assertThat(first.high()).isEqualByComparingTo(new BigDecimal("2950.00"));
        assertThat(first.low()).isEqualByComparingTo(new BigDecimal("2890.00"));
        assertThat(first.close()).isEqualByComparingTo(new BigDecimal("2940.00"));
        assertThat(first.previousClose()).isEqualByComparingTo(new BigDecimal("2895.00"));
        assertThat(first.volume()).isEqualTo(1200000L);
    }

    @Test
    void queriesDayItemsDescendingWithDaysAsLimit() {
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());

        dailyMarketDataQuery.findDailyPoints("TCS.NS", "45");

        ArgumentCaptor<software.amazon.awssdk.services.dynamodb.model.QueryRequest> captor =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class);
        verify(dynamoDb).query(captor.capture());

        var request = captor.getValue();
        assertThat(request.tableName()).isEqualTo(TABLE);
        assertThat(request.scanIndexForward()).isFalse();
        assertThat(request.limit()).isEqualTo(45);
        assertThat(request.keyConditionExpression()).isEqualTo("PK = :pk AND begins_with(SK, :sk)");
        assertThat(request.expressionAttributeValues()).containsValue(str("TICKER#TCS.NS"));
        assertThat(request.expressionAttributeValues()).containsValue(str("DAY#"));
    }

    @Test
    void defaultsDaysTo30WhenAbsentOrBlank() {
        assertThat(DailyMarketDataQuery.parseDays(null)).isEqualTo(30);
        assertThat(DailyMarketDataQuery.parseDays("")).isEqualTo(30);
        assertThat(DailyMarketDataQuery.parseDays("  ")).isEqualTo(30);
    }

    @Test
    void clampsNonNumericDaysTo30Silently() {
        assertThat(DailyMarketDataQuery.parseDays("thirty")).isEqualTo(30);
        assertThat(DailyMarketDataQuery.parseDays("3o")).isEqualTo(30);
    }

    @Test
    void capsDaysAt260AndFloorsAt1() {
        assertThat(DailyMarketDataQuery.parseDays("500")).isEqualTo(260);
        assertThat(DailyMarketDataQuery.parseDays("0")).isEqualTo(1);
        assertThat(DailyMarketDataQuery.parseDays("-5")).isEqualTo(1);
        assertThat(DailyMarketDataQuery.parseDays("260")).isEqualTo(260);
        assertThat(DailyMarketDataQuery.parseDays("1")).isEqualTo(1);
    }

    @Test
    void returnsNotFoundWhenNoDayItemsExist() {
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());

        DailyMarketDataResponse response = dailyMarketDataQuery.findDailyPoints("INFY.NS", "30");

        assertThat(response.found()).isFalse();
        assertThat(response.ticker()).isEqualTo("INFY.NS");
        assertThat(response.days()).isEmpty();
    }

    @Test
    void rejectsMalformedTickerWithoutTouchingDynamoDb() {
        assertThatThrownBy(() -> dailyMarketDataQuery.findDailyPoints("bad ticker", "30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid ticker:");
        verify(dynamoDb, never()).query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class));
    }

    @Test
    void fallsBackToSkSuffixWhenDayAttributeAbsent() {
        Map<String, AttributeValue> item = Map.of("SK", str("DAY#2026-07-10"), "close", num("100.00"));
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of(item)).build());

        DailyMarketDataResponse response = dailyMarketDataQuery.findDailyPoints("TCS.NS", "10");

        assertThat(response.days().getFirst().date()).isEqualTo("2026-07-10");
    }

    private static AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue num(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
