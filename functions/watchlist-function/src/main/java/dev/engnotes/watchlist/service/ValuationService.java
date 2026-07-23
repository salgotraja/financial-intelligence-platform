package dev.engnotes.watchlist.service;

import dev.engnotes.watchlist.model.HoldingValuation;
import dev.engnotes.watchlist.model.PortfolioValuation;
import dev.engnotes.watchlist.portfolio.HoldingMath;
import dev.engnotes.watchlist.portfolio.MoneyScale;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Prices an owner's holdings into a {@link PortfolioValuation} using the shared platform table's
 * market-data items (spec section 4): the latest intraday point (PK {@code TICKER#{ticker}}, SK
 * begins_with {@code TS#}, 24h TTL) and the latest two daily rollups (SK begins_with {@code DAY#},
 * no TTL). Per ticker, {@code ltp} prefers the TS# point and falls back to the newest DAY# close
 * when no intraday point exists (weekend/after-hours); a ticker with neither is DEGRADED, appears in
 * {@code holdings} with null priced fields, and is excluded from the portfolio totals.
 *
 * <p>{@code dayChange} additionally requires the two most recent DAY# rollups to be consecutive
 * trading days (1-4 calendar days apart, covering weekends/long weekends); otherwise it is null
 * without degrading the row.
 *
 * <p>Portfolio-level {@code asOf} is the lexicographically smallest non-null per-holding {@code
 * asOf}. This is a deliberate simplification: within one {@code asOf} "kind" (either all ISO-8601
 * timestamps or all {@code yyyy-MM-dd} dates), lexicographic order matches chronological order, and
 * that is accepted as good enough for a summary "as of" label without unifying the two temporal
 * formats.
 */
@Service
public class ValuationService {

    private static final Logger log = LoggerFactory.getLogger(ValuationService.class);

    private static final int MIN_CONSECUTIVE_DAYS = 1;
    private static final int MAX_CONSECUTIVE_DAYS = 4;

    private final HoldingsStoreService holdings;
    private final DynamoDbClient dynamoDb;
    private final String platformTable;
    private final Clock clock;

    public ValuationService(
            HoldingsStoreService holdings,
            DynamoDbClient dynamoDb,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable,
            Clock clock) {
        this.holdings = holdings;
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
        this.clock = clock;
    }

    /** Prices every holding for {@code ownerSub} and sums the non-degraded rows into portfolio totals. */
    public PortfolioValuation value(String ownerSub) {
        long startNanos = System.nanoTime();
        List<StoredHolding> stored = holdings.list(ownerSub);

        List<HoldingValuation> views = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        BigDecimal totalDayChange = BigDecimal.ZERO;
        String oldestAsOf = null;
        int degradedCount = 0;

        for (StoredHolding holding : stored) {
            String ticker = holding.holding().ticker();
            long qty = HoldingMath.totalQty(holding.holding().lots());
            BigDecimal avgCost = HoldingMath.avgCost(holding.holding().lots());

            PriceLookup priceLookup = latestPrice(ticker);
            DayRollups dayRollups = latestDayRollups(ticker);

            BigDecimal ltp = priceLookup.price() != null ? priceLookup.price() : dayRollups.close0();
            String asOf = priceLookup.price() != null ? priceLookup.timestamp() : dayRollups.day0();
            boolean degraded = ltp == null;

            BigDecimal dayChangeInternal = null;
            if (dayRollups.close0() != null
                    && dayRollups.close1() != null
                    && consecutiveTradingDays(dayRollups.day1(), dayRollups.day0())) {
                BigDecimal perShare = dayRollups.close0().subtract(dayRollups.close1());
                dayChangeInternal =
                        perShare.multiply(BigDecimal.valueOf(qty)).setScale(MoneyScale.INTERNAL, RoundingMode.HALF_UP);
            }

            BigDecimal pnlInternal = null;
            BigDecimal pnlPctInternal = null;
            if (!degraded) {
                BigDecimal marketValueInternal =
                        ltp.multiply(BigDecimal.valueOf(qty)).setScale(MoneyScale.INTERNAL, RoundingMode.HALF_UP);
                BigDecimal costInternal =
                        avgCost.multiply(BigDecimal.valueOf(qty)).setScale(MoneyScale.INTERNAL, RoundingMode.HALF_UP);
                pnlInternal = marketValueInternal.subtract(costInternal);
                pnlPctInternal = costInternal.signum() == 0
                        ? BigDecimal.ZERO
                        : pnlInternal
                                .divide(costInternal, MoneyScale.INTERNAL, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));

                totalValue = totalValue.add(marketValueInternal);
                totalCost = totalCost.add(costInternal);
                totalPnl = totalPnl.add(pnlInternal);
                if (dayChangeInternal != null) {
                    totalDayChange = totalDayChange.add(dayChangeInternal);
                }
            } else {
                degradedCount++;
            }

            if (asOf != null && (oldestAsOf == null || asOf.compareTo(oldestAsOf) < 0)) {
                oldestAsOf = asOf;
            }

            views.add(new HoldingValuation(
                    ticker,
                    qty,
                    MoneyScale.toDisplay(avgCost),
                    ltp == null ? null : MoneyScale.toDisplay(ltp),
                    dayChangeInternal == null ? null : MoneyScale.toDisplay(dayChangeInternal),
                    pnlInternal == null ? null : MoneyScale.toDisplay(pnlInternal),
                    pnlPctInternal == null ? null : MoneyScale.toDisplay(pnlPctInternal),
                    asOf,
                    degraded));
        }

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info(
                "Valued portfolio. owner={} tickers={} degraded={} asOf={} durationMs={}",
                ownerSub,
                views.size(),
                degradedCount,
                oldestAsOf,
                durationMs);

        return new PortfolioValuation(
                oldestAsOf,
                MoneyScale.toDisplay(totalValue),
                MoneyScale.toDisplay(totalCost),
                MoneyScale.toDisplay(totalPnl),
                MoneyScale.toDisplay(totalDayChange),
                views);
    }

    private PriceLookup latestPrice(String ticker) {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(":pk", s("TICKER#" + ticker), ":sk", s("TS#")))
                .scanIndexForward(false) // newest timestamp first
                .limit(1)
                .build();
        List<Map<String, AttributeValue>> items = dynamoDb.query(request).items();
        if (items.isEmpty()) {
            return new PriceLookup(null, null);
        }
        Map<String, AttributeValue> item = items.getFirst();
        return new PriceLookup(decimal(item, "price"), str(item, "timestamp"));
    }

    private DayRollups latestDayRollups(String ticker) {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(":pk", s("TICKER#" + ticker), ":sk", s("DAY#")))
                .scanIndexForward(false) // newest trading day first
                .limit(2)
                .build();
        List<Map<String, AttributeValue>> items = dynamoDb.query(request).items();
        BigDecimal close0 = null;
        String day0 = null;
        BigDecimal close1 = null;
        String day1 = null;
        if (!items.isEmpty()) {
            close0 = decimal(items.getFirst(), "close");
            day0 = str(items.getFirst(), "day");
        }
        if (items.size() > 1) {
            close1 = decimal(items.get(1), "close");
            day1 = str(items.get(1), "day");
        }
        return new DayRollups(close0, day0, close1, day1);
    }

    private static boolean consecutiveTradingDays(String olderDay, String newerDay) {
        long between = ChronoUnit.DAYS.between(LocalDate.parse(olderDay), LocalDate.parse(newerDay));
        return between >= MIN_CONSECUTIVE_DAYS && between <= MAX_CONSECUTIVE_DAYS;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static BigDecimal decimal(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? null : new BigDecimal(value.n());
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private record PriceLookup(BigDecimal price, String timestamp) {}

    private record DayRollups(BigDecimal close0, String day0, BigDecimal close1, String day1) {}
}
