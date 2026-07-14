package dev.engnotes.query.service;

import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.InsightFeedResponse;
import dev.engnotes.query.model.QueryResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * mirror items {@code GroupInsightStoreService} writes, and reuses {@link InsightQuery} for the
 * ticker's own latest per-ticker insight (an ungrouped ticker has no GSI1 mirror). Group insights
 * dedupe by {@code groupId} keeping the newest; a per-ticker insight is dropped when a deduped
 * group insight covers the same ticker with a {@code generatedAt} at or after the per-ticker one,
 * so the same event never appears twice. The combined list sorts newest first and caps at 25.
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

        List<FeedInsight> perTickerInsights = tickers.stream()
                .map(insightQuery::findLatestInsight)
                .filter(QueryResponse::found)
                .map(InsightFeedQuery::toFeedInsight)
                .filter(insight -> !supersededByGroup(insight, groupInsights))
                .toList();

        List<FeedInsight> combined = Stream.concat(groupInsights.stream(), perTickerInsights.stream())
                .sorted(comparingGeneratedAtDescending())
                .limit(FEED_CAP)
                .toList();

        log.info("Insight feed assembled. owner={} tickers={} insights={}", ownerSub, tickers.size(), combined.size());
        return new InsightFeedResponse(combined, !combined.isEmpty());
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
                .map(InsightFeedQuery::toGroupFeedInsight)
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

    private static FeedInsight toFeedInsight(QueryResponse response) {
        return new FeedInsight(
                null,
                List.of(response.ticker()),
                response.generatedAt(),
                response.signal(),
                response.confidence(),
                response.rationale(),
                response.drivers(),
                response.source());
    }

    private static FeedInsight toGroupFeedInsight(Map<String, AttributeValue> item) {
        return new FeedInsight(
                attr(item, "groupId"),
                stringList(item, "tickers"),
                attr(item, "generatedAt"),
                attr(item, "signal"),
                number(item, "confidence"),
                attr(item, "rationale"),
                stringList(item, "drivers"),
                attr(item, "source"));
    }

    private static String attr(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static double number(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? 0.0 : Double.parseDouble(value.n());
    }

    private static List<String> stringList(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        if (value == null || !value.hasL()) {
            return List.of();
        }
        return value.l().stream().map(AttributeValue::s).toList();
    }
}
