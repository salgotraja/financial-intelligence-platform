package dev.engnotes.watchlist.service;

import dev.engnotes.watchlist.model.Holding;
import dev.engnotes.watchlist.model.Lot;
import dev.engnotes.watchlist.portfolio.HoldingMath;
import dev.engnotes.watchlist.portfolio.LotMutationDetector;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Reads and writes portfolio holdings on the single platform table (spec section 4): PK
 * {@code USER#{sub}}, SK {@code HOLDING#{ticker}}. Each item is a full-replace snapshot of the
 * ticker's lots plus the derived {@code totalQty}/{@code avgCost} ({@link HoldingMath}) and the
 * {@code lastLotMutation} bookkeeping timestamp that {@link LotMutationDetector} decides whether to
 * bump. {@code upsert} also calls {@link WatchlistStoreService#add} so every tracked holding ticker
 * is on the watchlist union, which is what triggers the ingestion fan-out's 1-year DAY# backfill.
 */
@Service
public class HoldingsStoreService {

    private static final Logger log = LoggerFactory.getLogger(HoldingsStoreService.class);

    private final DynamoDbClient dynamoDb;
    private final WatchlistStoreService watchlist;
    private final String platformTable;
    private final Clock clock;

    public HoldingsStoreService(
            DynamoDbClient dynamoDb,
            WatchlistStoreService watchlist,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable,
            Clock clock) {
        this.dynamoDb = dynamoDb;
        this.watchlist = watchlist;
        this.platformTable = platformTable;
        this.clock = clock;
    }

    /** Reads a single holding, or empty if the ticker has no HOLDING# item for this owner. */
    public Optional<StoredHolding> get(String ownerSub, String ticker) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(platformTable)
                .key(holdingKey(ownerSub, ticker))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toStoredHolding(response.item()));
    }

    /** Lists all holdings for the owner (PK={@code USER#{sub}}, SK begins_with {@code HOLDING#}). Paginates. */
    public List<StoredHolding> list(String ownerSub) {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(":pk", s("USER#" + ownerSub), ":sk", s("HOLDING#")))
                .build();
        return dynamoDb.queryPaginator(request).items().stream()
                .map(this::toStoredHolding)
                .toList();
    }

    /**
     * Full-replace write: {@code newLots} becomes the ticker's entire lot list. Determines whether an
     * existing lot was mutated (edited or removed) via {@link LotMutationDetector}; if so
     * {@code lastLotMutation} is bumped to now, otherwise the prior stored value is preserved
     * (absent if never set). Also ensures the ticker is on the watchlist union so the ingestion
     * fan-out backfills its history.
     */
    public void upsert(String ownerSub, String ticker, List<Lot> newLots) {
        Holding holding = new Holding(ticker, newLots);
        Optional<StoredHolding> existing = get(ownerSub, ticker);
        List<Lot> oldLots = existing.map(stored -> stored.holding().lots()).orElse(List.of());
        boolean mutated = LotMutationDetector.isExistingLotMutation(oldLots, holding.lots());
        Instant now = Instant.now(clock);
        Instant lastLotMutation =
                mutated ? now : existing.map(StoredHolding::lastLotMutation).orElse(null);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", s("USER#" + ownerSub));
        item.put("SK", s("HOLDING#" + ticker));
        item.put("ticker", s(ticker));
        item.put(
                "lots",
                AttributeValue.builder()
                        .l(holding.lots().stream().map(this::toLotAttribute).toList())
                        .build());
        item.put("totalQty", n(HoldingMath.totalQty(holding.lots())));
        item.put("avgCost", n(HoldingMath.avgCost(holding.lots())));
        if (lastLotMutation != null) {
            item.put("lastLotMutation", s(lastLotMutation.toString()));
        }
        item.put("updatedAt", s(now.toString()));

        dynamoDb.putItem(
                PutItemRequest.builder().tableName(platformTable).item(item).build());
        watchlist.add(ownerSub, ticker);
        log.info(
                "Upserted holding. ticker={} owner={} lots={} mutated={}",
                ticker,
                ownerSub,
                holding.lots().size(),
                mutated);
    }

    /** Deletes the holding item. Idempotent. Does not touch the watchlist entry for the ticker. */
    public void delete(String ownerSub, String ticker) {
        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(platformTable)
                .key(holdingKey(ownerSub, ticker))
                .build());
        log.info("Deleted holding. ticker={} owner={}", ticker, ownerSub);
    }

    private Map<String, AttributeValue> holdingKey(String ownerSub, String ticker) {
        return Map.of("PK", s("USER#" + ownerSub), "SK", s("HOLDING#" + ticker));
    }

    private StoredHolding toStoredHolding(Map<String, AttributeValue> item) {
        List<Lot> lots = item.get("lots").l().stream().map(this::toLot).toList();
        Holding holding = new Holding(item.get("ticker").s(), lots);
        AttributeValue lastLotMutationAttr = item.get("lastLotMutation");
        Instant lastLotMutation = lastLotMutationAttr != null ? Instant.parse(lastLotMutationAttr.s()) : null;
        Instant updatedAt = Instant.parse(item.get("updatedAt").s());
        return new StoredHolding(holding, lastLotMutation, updatedAt);
    }

    private AttributeValue toLotAttribute(Lot lot) {
        return AttributeValue.builder()
                .m(Map.of(
                        "buyDate", s(lot.buyDate().toString()),
                        "qty", n(lot.qty()),
                        "price", n(lot.price())))
                .build();
    }

    private Lot toLot(AttributeValue value) {
        Map<String, AttributeValue> m = value.m();
        return new Lot(
                LocalDate.parse(m.get("buyDate").s()),
                Integer.parseInt(m.get("qty").n()),
                new BigDecimal(m.get("price").n()));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static AttributeValue n(BigDecimal value) {
        return AttributeValue.builder().n(value.toString()).build();
    }
}
