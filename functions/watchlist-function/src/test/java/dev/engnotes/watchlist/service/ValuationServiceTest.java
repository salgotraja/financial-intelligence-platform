package dev.engnotes.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import dev.engnotes.observability.Metrics;
import dev.engnotes.watchlist.model.Holding;
import dev.engnotes.watchlist.model.Lot;
import dev.engnotes.watchlist.model.PortfolioValuation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

@ExtendWith(MockitoExtension.class)
class ValuationServiceTest {

    private static final String TABLE = "financial-platform-test";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private HoldingsStoreService holdings;

    @Mock
    private DynamoDbClient dynamoDb;

    private final Metrics.Capture capture = Metrics.forTesting();

    private ValuationService service() {
        return new ValuationService(holdings, dynamoDb, TABLE, FIXED_CLOCK, capture.metrics());
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static Map<String, AttributeValue> tsItem(String ticker, String timestamp, String price) {
        return Map.of(
                "PK", s("TICKER#" + ticker),
                "SK", s("TS#" + timestamp),
                "price", n(price),
                "timestamp", s(timestamp));
    }

    private static Map<String, AttributeValue> dayItem(String ticker, String day, String close) {
        return Map.of(
                "PK", s("TICKER#" + ticker),
                "SK", s("DAY#" + day),
                "close", n(close),
                "day", s(day));
    }

    private static boolean isTsQuery(QueryRequest request, String ticker) {
        return request.expressionAttributeValues().get(":pk").s().equals("TICKER#" + ticker)
                && request.expressionAttributeValues().get(":sk").s().equals("TS#");
    }

    private static boolean isDayQuery(QueryRequest request, String ticker) {
        return request.expressionAttributeValues().get(":pk").s().equals("TICKER#" + ticker)
                && request.expressionAttributeValues().get(":sk").s().equals("DAY#");
    }

    private void stubTs(String ticker, Map<String, AttributeValue>... items) {
        when(dynamoDb.query(argThat((QueryRequest r) -> r != null && isTsQuery(r, ticker))))
                .thenReturn(QueryResponse.builder().items(List.of(items)).build());
    }

    private void stubDay(String ticker, Map<String, AttributeValue>... items) {
        when(dynamoDb.query(argThat((QueryRequest r) -> r != null && isDayQuery(r, ticker))))
                .thenReturn(QueryResponse.builder().items(List.of(items)).build());
    }

    private static StoredHolding holdingOf(String ticker, Lot... lots) {
        return new StoredHolding(new Holding(ticker, List.of(lots)), null, Instant.parse("2026-07-22T00:00:00Z"));
    }

    @Test
    void pricesFromLatestTsPointAndComputesPnl() {
        Lot lot = new Lot(LocalDate.parse("2020-01-15"), 10, new BigDecimal("100"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("RELIANCE.NS", lot)));
        stubTs("RELIANCE.NS", tsItem("RELIANCE.NS", "2026-07-23T10:00:00Z", "120"));
        stubDay(
                "RELIANCE.NS",
                dayItem("RELIANCE.NS", "2026-07-22", "118"),
                dayItem("RELIANCE.NS", "2026-07-21", "110"));

        PortfolioValuation valuation = service().value("owner-1");

        assertThat(valuation.holdings()).hasSize(1);
        var view = valuation.holdings().get(0);
        assertThat(view.degraded()).isFalse();
        assertThat(view.ltp()).isEqualByComparingTo("120.00");
        assertThat(view.asOf()).isEqualTo("2026-07-23T10:00:00Z");
        assertThat(view.dayChange()).isEqualByComparingTo("80.00");
        assertThat(view.pnl()).isEqualByComparingTo("200.00");
        assertThat(view.pnlPct()).isEqualByComparingTo("20.00");
        assertThat(valuation.totalValue()).isEqualByComparingTo("1200.00");
        assertThat(valuation.totalCost()).isEqualByComparingTo("1000.00");
        assertThat(valuation.totalPnl()).isEqualByComparingTo("200.00");
        assertThat(valuation.totalDayChange()).isEqualByComparingTo("80.00");
        assertThat(valuation.asOf()).isEqualTo("2026-07-23T10:00:00Z");
        assertThat(capture.records()).noneMatch(record -> record.contains("\"PortfolioValuationDegradedRows\""));
    }

    @Test
    void fallsBackToLatestDayCloseWhenNoTsPoint() {
        Lot lot = new Lot(LocalDate.parse("2020-01-15"), 10, new BigDecimal("100"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("RELIANCE.NS", lot)));
        stubTs("RELIANCE.NS");
        stubDay("RELIANCE.NS", dayItem("RELIANCE.NS", "2026-07-22", "118"));

        PortfolioValuation valuation = service().value("owner-1");

        var view = valuation.holdings().get(0);
        assertThat(view.degraded()).isFalse();
        assertThat(view.ltp()).isEqualByComparingTo("118.00");
        assertThat(view.asOf()).isEqualTo("2026-07-22");
    }

    @Test
    void degradesWhenNoPriceData() {
        Lot lot = new Lot(LocalDate.parse("2020-01-15"), 10, new BigDecimal("100"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("RELIANCE.NS", lot)));
        stubTs("RELIANCE.NS");
        stubDay("RELIANCE.NS");

        PortfolioValuation valuation = service().value("owner-1");

        var view = valuation.holdings().get(0);
        assertThat(view.degraded()).isTrue();
        assertThat(view.ltp()).isNull();
        assertThat(view.pnl()).isNull();
        assertThat(view.dayChange()).isNull();
        assertThat(valuation.totalValue()).isEqualByComparingTo("0.00");
        assertThat(capture.records())
                .anySatisfy(record -> assertThat(record).contains("\"PortfolioValuationDegradedRows\""));
    }

    @Test
    void dayChangeNullWhenDayRollupsNotConsecutive() {
        Lot lot = new Lot(LocalDate.parse("2020-01-15"), 10, new BigDecimal("100"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("RELIANCE.NS", lot)));
        stubTs("RELIANCE.NS", tsItem("RELIANCE.NS", "2026-07-23T10:00:00Z", "120"));
        stubDay("RELIANCE.NS", dayItem("RELIANCE.NS", "2026-07-22", "118"), dayItem("RELIANCE.NS", "2026-07-12", "90"));

        PortfolioValuation valuation = service().value("owner-1");

        var view = valuation.holdings().get(0);
        assertThat(view.dayChange()).isNull();
        assertThat(view.degraded()).isFalse();
        assertThat(view.ltp()).isEqualByComparingTo("120.00");
        assertThat(view.pnl()).isEqualByComparingTo("200.00");
    }

    @Test
    void dayChangeNullWhenOnlyOneDayRollup() {
        Lot lot = new Lot(LocalDate.parse("2020-01-15"), 10, new BigDecimal("100"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("RELIANCE.NS", lot)));
        stubTs("RELIANCE.NS", tsItem("RELIANCE.NS", "2026-07-23T10:00:00Z", "120"));
        stubDay("RELIANCE.NS", dayItem("RELIANCE.NS", "2026-07-22", "118"));

        PortfolioValuation valuation = service().value("owner-1");

        var view = valuation.holdings().get(0);
        assertThat(view.dayChange()).isNull();
        assertThat(view.degraded()).isFalse();
    }

    @Test
    void nonTerminatingPnlPctDoesNotThrow() {
        Lot lot = new Lot(LocalDate.parse("2020-01-15"), 3, new BigDecimal("100"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("RELIANCE.NS", lot)));
        stubTs("RELIANCE.NS", tsItem("RELIANCE.NS", "2026-07-23T10:00:00Z", "133.33333333"));
        stubDay("RELIANCE.NS");

        assertThatCode(() -> service().value("owner-1")).doesNotThrowAnyException();
        var view = service().value("owner-1").holdings().get(0);
        assertThat(view.pnlPct().scale()).isEqualTo(2);
    }

    @Test
    void summaryAsOfIsOldestAcrossHoldings() {
        Lot lot = new Lot(LocalDate.parse("2020-01-15"), 10, new BigDecimal("100"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("RELIANCE.NS", lot), holdingOf("TCS.NS", lot)));
        stubTs("RELIANCE.NS", tsItem("RELIANCE.NS", "2026-07-23T10:00:00Z", "120"));
        stubDay("RELIANCE.NS");
        stubTs("TCS.NS");
        stubDay("TCS.NS", dayItem("TCS.NS", "2026-07-10", "3000"));

        PortfolioValuation valuation = service().value("owner-1");

        assertThat(valuation.asOf()).isEqualTo("2026-07-10");
    }
}
