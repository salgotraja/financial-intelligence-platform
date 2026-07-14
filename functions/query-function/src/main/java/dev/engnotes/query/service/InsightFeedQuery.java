package dev.engnotes.query.service;

import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.InsightFeedResponse;
import dev.engnotes.query.model.QueryResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Assembles the watchlist insight feed (GET /insights, spec section 10): every group insight
 * touching a watched ticker, plus each ungrouped ticker's own latest insight, deduped and capped.
 *
 * <p>Design: reads the caller's watchlist (PK=USER#{sub}, SK begins_with WATCH#), then for each
 * ticker queries GSI1 (GSI1PK=TICKER#{ticker}, newest-first, small limit) for the group-insight
 * mirror items {@code GroupInsightStoreService} writes. Group insights dedupe by {@code groupId}
 * keeping the newest. A ticker already covered by a deduped group insight never gets a per-ticker
 * {@link InsightQuery} read at all: {@code InsightGenerationService} writes every group member's
 * per-ticker item with the group's own {@code generatedAt} in the same generation, so a covered
 * ticker's own latest can never be stored with a fresher timestamp - the group entry is always the
 * observably newest for that ticker, and skipping the read halves read volume on group-heavy
 * watchlists. Only an ungrouped ticker (no covering mirror) pays for the per-ticker read. The
 * combined list sorts newest first and caps at 25. ({@link #latestForTicker}, a single-ticker read,
 * keeps the precise timestamp-compared supersession below since it has no read volume to save.)
 *
 * <p>Feed assembly is item-tolerant: an item with a missing or unparseable {@code generatedAt}, or
 * a malformed list attribute, is skipped with a warning at the mapping boundary rather than thrown,
 * so one bad stored item can never fail the whole feed (matching
 * {@code GroupInsightStoreService.latestGeneratedAt}'s degrade-gracefully precedent). Everything
 * past the mapping boundary can therefore parse {@code generatedAt} unguarded.
 */
@Service
public class InsightFeedQuery {

    private static final Logger log = LoggerFactory.getLogger(InsightFeedQuery.class);

    private static final String GSI1_INDEX = "GSI1";
    private static final int GROUP_QUERY_LIMIT = 5;
    private static final int FEED_CAP = 25;

    private final DynamoDbClient dynamoDb;
    private final String platformTable;
    private final InsightQuery insightQuery;

    public InsightFeedQuery(
            DynamoDbClient dynamoDb,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable,
            InsightQuery insightQuery) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
        this.insightQuery = insightQuery;
    }

    public InsightFeedResponse feed(String ownerSub) {
        List<String> tickers = watchlistTickers(ownerSub);
        if (tickers.isEmpty()) {
            log.info("Empty watchlist, empty insight feed. owner={}", ownerSub);
            return InsightFeedResponse.empty();
        }

        List<FeedInsight> groupInsights = dedupeByGroupId(tickers.stream()
                .flatMap(ticker -> queryGroupInsights(ticker).stream())
                .toList());
        Set<String> coveredByGroup = groupInsights.stream()
                .flatMap(insight -> insight.tickers().stream())
                .collect(Collectors.toSet());

        List<FeedInsight> perTickerInsights = tickers.stream()
                .filter(ticker -> !coveredByGroup.contains(ticker))
                .map(insightQuery::findLatestInsight)
                .filter(QueryResponse::found)
                .flatMap(response -> fromPerTickerResponse(response).stream())
                .toList();

        List<FeedInsight> combined = Stream.concat(groupInsights.stream(), perTickerInsights.stream())
                .sorted(comparingGeneratedAtDescending())
                .limit(FEED_CAP)
                .toList();

        log.info("Insight feed assembled. owner={} tickers={} insights={}", ownerSub, tickers.size(), combined.size());
        return new InsightFeedResponse(combined, !combined.isEmpty());
    }

    /**
     * Latest insight for a single ticker outside a watchlist context, group-aware: the newer of the
     * ticker's own per-ticker insight and any group insight covering it (spec sub-project C, Task
     * 16 - StoryQuery's third assembled input). Reuses the same GSI1-plus-per-ticker read {@link
     * #feed} performs per watched ticker. Delegates to {@link InsightQuery} first, which both
     * validates the ticker (throwing before the GSI1 query below would otherwise run) and returns
     * the canonical decoded ticker on {@link QueryResponse#ticker()} - reused for the GSI1 query so
     * a percent-encoded index symbol (e.g. {@code %5ENSEI}) resolves group insights under the same
     * decoded key ingestion wrote them under, matching every sibling query class's convention of
     * building DynamoDB key expressions only from an already-validated ticker.
     */
    public Optional<FeedInsight> latestForTicker(String ticker) {
        QueryResponse perTickerResponse = insightQuery.findLatestInsight(ticker);
        String canonicalTicker = perTickerResponse.ticker();
        List<FeedInsight> groupInsights = dedupeByGroupId(queryGroupInsights(canonicalTicker));
        Optional<FeedInsight> perTicker = perTickerResponse.found()
                ? fromPerTickerResponse(perTickerResponse).filter(insight -> !supersededByGroup(insight, groupInsights))
                : Optional.empty();
        return Stream.concat(groupInsights.stream(), perTicker.stream())
                .max(Comparator.comparing(insight -> Instant.parse(insight.generatedAt())));
    }

    private List<String> watchlistTickers(String ownerSub) {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s("USER#" + ownerSub).build(),
                        ":sk", AttributeValue.builder().s("WATCH#").build()))
                .build();
        return dynamoDb.queryPaginator(request).items().stream()
                .map(item -> item.get("ticker"))
                .filter(Objects::nonNull)
                .map(AttributeValue::s)
                .toList();
    }

    private List<FeedInsight> queryGroupInsights(String ticker) {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .indexName(GSI1_INDEX)
                .keyConditionExpression("GSI1PK = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s("TICKER#" + ticker).build()))
                .scanIndexForward(false) // newest generatedAt first
                .limit(GROUP_QUERY_LIMIT)
                .build();
        return dynamoDb.query(request).items().stream()
                .flatMap(item -> fromGroupItem(item).stream())
                .toList();
    }

    /** Keeps the newest item per groupId; insertion order otherwise irrelevant, the caller re-sorts. */
    private static List<FeedInsight> dedupeByGroupId(List<FeedInsight> groupInsights) {
        Map<String, FeedInsight> newestByGroup = new LinkedHashMap<>();
        for (FeedInsight insight : groupInsights) {
            newestByGroup.merge(insight.groupId(), insight, InsightFeedQuery::newer);
        }
        return List.copyOf(newestByGroup.values());
    }

    private static FeedInsight newer(FeedInsight a, FeedInsight b) {
        return Instant.parse(b.generatedAt()).isAfter(Instant.parse(a.generatedAt())) ? b : a;
    }

    private static boolean supersededByGroup(FeedInsight perTicker, List<FeedInsight> groupInsights) {
        String ticker = perTicker.tickers().getFirst();
        Instant perTickerTime = Instant.parse(perTicker.generatedAt());
        return groupInsights.stream()
                .anyMatch(group -> group.tickers().contains(ticker)
                        && !Instant.parse(group.generatedAt()).isBefore(perTickerTime));
    }

    private static Comparator<FeedInsight> comparingGeneratedAtDescending() {
        return Comparator.comparing((FeedInsight insight) -> Instant.parse(insight.generatedAt()))
                .reversed();
    }

    /** Empty (skipped, one warn) when generatedAt is missing/unparseable: never fails the feed. */
    private static Optional<FeedInsight> fromPerTickerResponse(QueryResponse response) {
        if (parseInstant(response.generatedAt()).isEmpty()) {
            log.warn(
                    "Skipping per-ticker insight with missing/unparseable generatedAt. ticker={} generatedAt={}",
                    response.ticker(),
                    response.generatedAt());
            return Optional.empty();
        }
        return Optional.of(new FeedInsight(
                null,
                List.of(response.ticker()),
                response.generatedAt(),
                response.signal(),
                response.confidence(),
                response.rationale(),
                response.drivers(),
                response.source()));
    }

    /**
     * Empty (skipped, one warn) when the GSI1 mirror item is malformed: missing groupId, missing or
     * unparseable generatedAt, or a tickers/drivers list holding non-string elements. One bad stored
     * item must never fail the whole feed.
     */
    private static Optional<FeedInsight> fromGroupItem(Map<String, AttributeValue> item) {
        String groupId = attr(item, "groupId");
        String generatedAt = attr(item, "generatedAt");
        Optional<List<String>> tickers = stringList(item, "tickers");
        Optional<List<String>> drivers = stringList(item, "drivers");
        if (groupId == null || parseInstant(generatedAt).isEmpty() || tickers.isEmpty() || drivers.isEmpty()) {
            log.warn("Skipping malformed group insight item. groupId={} generatedAt={}", groupId, generatedAt);
            return Optional.empty();
        }
        return Optional.of(new FeedInsight(
                groupId,
                tickers.get(),
                generatedAt,
                attr(item, "signal"),
                number(item, "confidence"),
                attr(item, "rationale"),
                drivers.get(),
                attr(item, "source")));
    }

    private static Optional<Instant> parseInstant(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }

    private static String attr(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static double number(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? 0.0 : Double.parseDouble(value.n());
    }

    /** Empty on a malformed list (any non-string element); an absent attribute is an empty list. */
    private static Optional<List<String>> stringList(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        if (value == null || !value.hasL()) {
            return Optional.of(List.of());
        }
        List<String> strings = value.l().stream()
                .map(AttributeValue::s)
                .filter(Objects::nonNull)
                .toList();
        if (strings.size() != value.l().size()) {
            return Optional.empty();
        }
        return Optional.of(strings);
    }
}
