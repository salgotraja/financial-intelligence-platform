package dev.engnotes.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import dev.engnotes.watchlist.model.Holding;
import dev.engnotes.watchlist.model.Lot;
import dev.engnotes.watchlist.model.PortfolioHistory;
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
class PortfolioHistoryServiceTest {

    private static final String TABLE = "financial-platform-test";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private HoldingsStoreService holdings;

    @Mock
    private DynamoDbClient dynamoDb;

    private PortfolioHistoryService service() {
        return new PortfolioHistoryService(holdings, dynamoDb, TABLE, FIXED_CLOCK);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static Map<String, AttributeValue> dayItem(String ticker, String day, String close) {
        return Map.of(
                "PK", s("TICKER#" + ticker),
                "SK", s("DAY#" + day),
                "close", n(close),
                "day", s(day));
    }

    @SafeVarargs
    private void stubDay(String ticker, Map<String, AttributeValue>... items) {
        when(dynamoDb.query(argThat((QueryRequest r) -> r != null
                        && r.expressionAttributeValues().get(":pk").s().equals("TICKER#" + ticker)
                        && r.expressionAttributeValues().get(":sk").s().equals("DAY#"))))
                .thenReturn(QueryResponse.builder().items(List.of(items)).build());
    }

    private static StoredHolding holdingOf(String ticker, Lot... lots) {
        return new StoredHolding(new Holding(ticker, List.of(lots)), null, Instant.parse("2026-07-22T00:00:00Z"));
    }

    private static StoredHolding holdingWithMutation(String ticker, Instant lastLotMutation, Lot... lots) {
        return new StoredHolding(
                new Holding(ticker, List.of(lots)), lastLotMutation, Instant.parse("2026-07-22T00:00:00Z"));
    }

    @Test
    void buildsCurveFromDailyCloses() {
        Lot lot = new Lot(LocalDate.parse("2026-07-20"), 10, new BigDecimal("100"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("RELIANCE.NS", lot)));
        stubDay(
                "RELIANCE.NS",
                dayItem("RELIANCE.NS", "2026-07-20", "100"),
                dayItem("RELIANCE.NS", "2026-07-21", "105"),
                dayItem("RELIANCE.NS", "2026-07-22", "110"),
                dayItem("RELIANCE.NS", "2026-07-23", "115"));

        PortfolioHistory history = service().history("owner-1");

        assertThat(history.floor()).isEqualTo("2026-07-20");
        assertThat(history.asOf()).isEqualTo("2026-07-23");
        assertThat(history.degradedTickers()).isEmpty();
        assertThat(history.points()).hasSize(4);
        assertThat(history.points().get(0).day()).isEqualTo("2026-07-20");
        assertThat(history.points().get(0).value()).isEqualByComparingTo("1000.00");
        assertThat(history.points().get(3).day()).isEqualTo("2026-07-23");
        assertThat(history.points().get(3).value()).isEqualByComparingTo("1150.00");
        assertThat(history.markers()).hasSize(1);
        assertThat(history.markers().get(0).day()).isEqualTo("2026-07-20");
        assertThat(history.markers().get(0).ticker()).isEqualTo("RELIANCE.NS");
        assertThat(history.markers().get(0).qty()).isEqualTo(10);
    }

    @Test
    void carriesForwardMissingRollupDays() {
        Lot lotA = new Lot(LocalDate.parse("2026-07-20"), 1, new BigDecimal("100"));
        Lot lotB = new Lot(LocalDate.parse("2026-07-20"), 1, new BigDecimal("50"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("A.NS", lotA), holdingOf("B.NS", lotB)));
        stubDay("A.NS", dayItem("A.NS", "2026-07-20", "100"), dayItem("A.NS", "2026-07-22", "104"));
        stubDay(
                "B.NS",
                dayItem("B.NS", "2026-07-20", "50"),
                dayItem("B.NS", "2026-07-21", "51"),
                dayItem("B.NS", "2026-07-22", "52"));

        PortfolioHistory history = service().history("owner-1");

        var mid = history.points().stream()
                .filter(p -> p.day().equals("2026-07-21"))
                .findFirst()
                .orElseThrow();
        // A carries forward its 07-20 close (100) since it has no 07-21 rollup; B has an actual close (51).
        assertThat(mid.value()).isEqualByComparingTo("151.00");
    }

    @Test
    void floorsAtLastLotMutation() {
        Lot lot = new Lot(LocalDate.parse("2026-07-10"), 5, new BigDecimal("90"));
        when(holdings.list("owner-1"))
                .thenReturn(List.of(holdingWithMutation("RELIANCE.NS", Instant.parse("2026-07-20T12:00:00Z"), lot)));
        stubDay(
                "RELIANCE.NS",
                dayItem("RELIANCE.NS", "2026-07-18", "95"),
                dayItem("RELIANCE.NS", "2026-07-19", "96"),
                dayItem("RELIANCE.NS", "2026-07-20", "97"),
                dayItem("RELIANCE.NS", "2026-07-21", "98"));

        PortfolioHistory history = service().history("owner-1");

        assertThat(history.floor()).isEqualTo("2026-07-20");
        assertThat(history.points()).extracting("day").doesNotContain("2026-07-18", "2026-07-19");
    }

    @Test
    void qtyRampsAsLotsVest() {
        Lot earlyLot = new Lot(LocalDate.parse("2026-07-18"), 5, new BigDecimal("90"));
        Lot laterLot = new Lot(LocalDate.parse("2026-07-21"), 3, new BigDecimal("95"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("RELIANCE.NS", earlyLot, laterLot)));
        stubDay(
                "RELIANCE.NS",
                dayItem("RELIANCE.NS", "2026-07-18", "100"),
                dayItem("RELIANCE.NS", "2026-07-19", "101"),
                dayItem("RELIANCE.NS", "2026-07-21", "103"));

        PortfolioHistory history = service().history("owner-1");

        var day19 = history.points().stream()
                .filter(p -> p.day().equals("2026-07-19"))
                .findFirst()
                .orElseThrow();
        // Only the early lot (qty 5) has vested by 07-19; the later lot (buyDate 07-21) has not.
        assertThat(day19.value()).isEqualByComparingTo("505.00");
    }

    @Test
    void degradedTickerWithNoRollups() {
        Lot lotA = new Lot(LocalDate.parse("2026-07-20"), 10, new BigDecimal("100"));
        Lot lotB = new Lot(LocalDate.parse("2026-07-20"), 5, new BigDecimal("50"));
        when(holdings.list("owner-1")).thenReturn(List.of(holdingOf("A.NS", lotA), holdingOf("B.NS", lotB)));
        stubDay("A.NS", dayItem("A.NS", "2026-07-20", "100"));
        stubDay("B.NS");

        PortfolioHistory history = service().history("owner-1");

        assertThat(history.degradedTickers()).containsExactly("B.NS");
        assertThat(history.points()).allSatisfy(p -> assertThat(p.value()).isEqualByComparingTo("1000.00"));
    }

    @Test
    void emptyHoldingsReturnsEmptyHistory() {
        when(holdings.list("owner-1")).thenReturn(List.of());

        PortfolioHistory history = service().history("owner-1");

        assertThat(history.floor()).isNull();
        assertThat(history.asOf()).isNull();
        assertThat(history.points()).isEmpty();
        assertThat(history.markers()).isEmpty();
        assertThat(history.degradedTickers()).isEmpty();
    }
}
