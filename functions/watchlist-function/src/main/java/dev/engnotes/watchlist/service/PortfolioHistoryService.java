package dev.engnotes.watchlist.service;

import dev.engnotes.watchlist.model.BuyMarker;
import dev.engnotes.watchlist.model.HistoryPoint;
import dev.engnotes.watchlist.model.Lot;
import dev.engnotes.watchlist.model.PortfolioHistory;
import dev.engnotes.watchlist.portfolio.MoneyScale;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Builds the "time-machine" portfolio value-curve for an owner from the shared platform table's DAY#
 * daily rollups (spec section 4): PK {@code TICKER#{ticker}}, SK begins_with {@code DAY#}, no TTL.
 * Unlike {@link ValuationService} (a live snapshot), this walks every rollup day for every held
 * ticker to reconstruct historical value.
 *
 * <p>The curve's {@code floor} is the latest of: the earliest day any held ticker has rollup data
 * for, or (if the holding's lots were ever edited/removed) the day of that last edit -
 * {@link StoredHolding#lastLotMutation()} - since lot history before an edit cannot be reconstructed
 * from the current lot list alone. A ticker with zero rollup data is DEGRADED: excluded from the
 * curve and floor calculation, listed in {@code degradedTickers}, but its lots still contribute
 * {@link BuyMarker}s.
 *
 * <p>Each curve day's value carries forward the most recent close on or before that day per ticker
 * (weekends/holidays have no rollup), and counts only lots bought on or before that day (a lot bought
 * later has not "vested" into the position yet).
 */
@Service
public class PortfolioHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioHistoryService.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final HoldingsStoreService holdings;
    private final DynamoDbClient dynamoDb;
    private final String platformTable;
    private final Clock clock;

    public PortfolioHistoryService(
            HoldingsStoreService holdings,
            DynamoDbClient dynamoDb,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable,
            Clock clock) {
        this.holdings = holdings;
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
        this.clock = clock;
    }

    /** Reconstructs {@code ownerSub}'s portfolio value-curve from every held ticker's daily rollups. */
    public PortfolioHistory history(String ownerSub) {
        long startNanos = System.nanoTime();
        List<StoredHolding> stored = holdings.list(ownerSub);
        if (stored.isEmpty()) {
            return new PortfolioHistory(null, null, List.of(), List.of(), List.of());
        }

        List<String> degradedTickers = new ArrayList<>();
        List<StoredHolding> withRollups = new ArrayList<>();
        Map<String, TreeMap<LocalDate, BigDecimal>> closeByDayByTicker = new HashMap<>();

        for (StoredHolding holding : stored) {
            String ticker = holding.holding().ticker();
            TreeMap<LocalDate, BigDecimal> closeByDay = dayRollups(ticker);
            if (closeByDay.isEmpty()) {
                degradedTickers.add(ticker);
            } else {
                withRollups.add(holding);
                closeByDayByTicker.put(ticker, closeByDay);
            }
        }
        degradedTickers.sort(Comparator.naturalOrder());

        LocalDate today = LocalDate.ofInstant(clock.instant(), IST);

        if (withRollups.isEmpty()) {
            log.info(
                    "Portfolio history. owner={} tickers={} degraded={} floor={} points={} durationMs={}",
                    ownerSub,
                    stored.size(),
                    degradedTickers.size(),
                    null,
                    0,
                    (System.nanoTime() - startNanos) / 1_000_000);
            return new PortfolioHistory(null, null, List.of(), List.of(), degradedTickers);
        }

        LocalDate floor = null;
        for (StoredHolding holding : withRollups) {
            String ticker = holding.holding().ticker();
            LocalDate earliestRollup = closeByDayByTicker.get(ticker).firstKey();
            LocalDate lastLotMutationDate =
                    holding.lastLotMutation() == null ? null : LocalDate.ofInstant(holding.lastLotMutation(), IST);
            LocalDate tickerFloor = lastLotMutationDate == null
                    ? earliestRollup
                    : (lastLotMutationDate.isAfter(earliestRollup) ? lastLotMutationDate : earliestRollup);
            floor = floor == null || tickerFloor.isAfter(floor) ? tickerFloor : floor;
        }

        TreeSet<LocalDate> curveDays = new TreeSet<>();
        for (StoredHolding holding : withRollups) {
            String ticker = holding.holding().ticker();
            for (LocalDate day : closeByDayByTicker.get(ticker).keySet()) {
                if (!day.isBefore(floor) && !day.isAfter(today)) {
                    curveDays.add(day);
                }
            }
        }

        List<HistoryPoint> points = new ArrayList<>();
        for (LocalDate day : curveDays) {
            BigDecimal valueInternal = BigDecimal.ZERO;
            for (StoredHolding holding : withRollups) {
                long qty = qtyHeldOn(holding.holding().lots(), day);
                if (qty == 0) {
                    continue;
                }
                Map.Entry<LocalDate, BigDecimal> floorEntry =
                        closeByDayByTicker.get(holding.holding().ticker()).floorEntry(day);
                if (floorEntry == null) {
                    continue;
                }
                valueInternal = valueInternal.add(floorEntry
                        .getValue()
                        .multiply(BigDecimal.valueOf(qty))
                        .setScale(MoneyScale.INTERNAL, RoundingMode.HALF_UP));
            }
            points.add(new HistoryPoint(day.toString(), MoneyScale.toDisplay(valueInternal)));
        }

        List<BuyMarker> markers = new ArrayList<>();
        for (StoredHolding holding : stored) {
            String ticker = holding.holding().ticker();
            for (Lot lot : holding.holding().lots()) {
                if (!lot.buyDate().isBefore(floor) && !lot.buyDate().isAfter(today)) {
                    markers.add(new BuyMarker(
                            lot.buyDate().toString(), ticker, lot.qty(), MoneyScale.toDisplay(lot.price())));
                }
            }
        }
        markers.sort(Comparator.comparing(BuyMarker::day).thenComparing(BuyMarker::ticker));

        String asOf = curveDays.isEmpty() ? null : curveDays.last().toString();

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info(
                "Portfolio history. owner={} tickers={} degraded={} floor={} points={} durationMs={}",
                ownerSub,
                withRollups.size(),
                degradedTickers.size(),
                floor,
                points.size(),
                durationMs);

        return new PortfolioHistory(floor.toString(), asOf, points, markers, degradedTickers);
    }

    private TreeMap<LocalDate, BigDecimal> dayRollups(String ticker) {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(":pk", s("TICKER#" + ticker), ":sk", s("DAY#")))
                .scanIndexForward(true)
                // Project only what the curve needs. Crucially NOT `series`, whose intraday points
                // dominate DAY# item size; without this a multi-year history could overflow the 1MB
                // query page and silently truncate the curve. `day`/`close` are reserved words.
                .projectionExpression("#day, #close")
                .expressionAttributeNames(Map.of("#day", "day", "#close", "close"))
                .build();
        TreeMap<LocalDate, BigDecimal> closeByDay = new TreeMap<>();
        for (Map<String, AttributeValue> item : dynamoDb.query(request).items()) {
            String day = str(item, "day");
            BigDecimal close = decimal(item, "close");
            if (day != null && close != null) {
                closeByDay.put(LocalDate.parse(day), close);
            }
        }
        return closeByDay;
    }

    private static long qtyHeldOn(List<Lot> lots, LocalDate day) {
        long qty = 0;
        for (Lot lot : lots) {
            if (!lot.buyDate().isAfter(day)) {
                qty += lot.qty();
            }
        }
        return qty;
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
}
