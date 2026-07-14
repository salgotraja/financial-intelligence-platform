package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.query.model.FeedInsight;
import dev.engnotes.query.model.InsightFeedResponse;
import dev.engnotes.query.model.QueryResponse;
import java.util.ArrayList;
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
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

@ExtendWith(MockitoExtension.class)
class InsightFeedQueryTest {

    private static final String TABLE = "financial-platform-test";
    private static final String OWNER = "user-123";

    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private InsightQuery insightQuery;

    private InsightFeedQuery feedQuery;

    @BeforeEach
    void setUp() {
        feedQuery = new InsightFeedQuery(dynamoDb, TABLE, insightQuery);
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
    }

    private void watchlist(String... tickers) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (String ticker : tickers) {
            items.add(Map.of("ticker", str(ticker)));
        }
        when(dynamoDb.query(any(QueryRequest.class))).thenAnswer(inv -> {
            QueryRequest request = inv.getArgument(0);
            if (request.indexName() == null) {
                return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(items)
                        .build();
            }
            return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                    .items(List.of())
                    .build();
        });
    }

    private void groupInsightsFor(String ticker, Map<String, AttributeValue>... items) {
        when(dynamoDb.query(any(QueryRequest.class))).thenAnswer(inv -> {
            QueryRequest request = inv.getArgument(0);
            if (request.indexName() == null) {
                return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(Map.of("ticker", str(ticker))))
                        .build();
            }
            String pk = request.expressionAttributeValues().get(":pk").s();
            if (pk.equals("TICKER#" + ticker)) {
                return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(items))
                        .build();
            }
            return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                    .items(List.of())
                    .build();
        });
    }

    @Test
    void emptyWatchlistReturnsEmptyFeedWithoutQueryingGsi1OrPerTickerInsights() {
        watchlist();

        InsightFeedResponse response = feedQuery.feed(OWNER);

        assertThat(response.found()).isFalse();
        assertThat(response.insights()).isEmpty();
        verify(insightQuery, never()).findLatestInsight(any());
    }

    @Test
    void dedupesGroupInsightsByGroupIdKeepingNewest() {
        groupInsightsFor(
                "A",
                groupItem("G1", "2026-07-14T10:00:00Z", "A", "B"),
                groupItem("G1", "2026-07-14T09:00:00Z", "A", "B"));
        when(insightQuery.findLatestInsight("A")).thenReturn(QueryResponse.notFound("A"));

        InsightFeedResponse response = feedQuery.feed(OWNER);

        assertThat(response.found()).isTrue();
        assertThat(response.insights()).hasSize(1);
        assertThat(response.insights().getFirst().groupId()).isEqualTo("G1");
        assertThat(response.insights().getFirst().generatedAt()).isEqualTo("2026-07-14T10:00:00Z");
    }

    @Test
    void perTickerInsightSupersededByNewerOrEqualGroupInsightCoveringTheSameTicker() {
        groupInsightsFor("A", groupItem("G1", "2026-07-14T10:00:00Z", "A"));
        when(insightQuery.findLatestInsight("A"))
                .thenReturn(new QueryResponse(
                        "A", "2026-07-14T09:00:00Z", "BULLISH", 0.7, "r", List.of(), "RULE_BASED", "r", "m", true));

        InsightFeedResponse response = feedQuery.feed(OWNER);

        assertThat(response.insights()).hasSize(1);
        assertThat(response.insights().getFirst().groupId()).isEqualTo("G1");
    }

    @Test
    void perTickerInsightSupersededWhenGroupGeneratedAtExactlyMatches() {
        groupInsightsFor("A", groupItem("G1", "2026-07-14T09:00:00Z", "A"));
        when(insightQuery.findLatestInsight("A"))
                .thenReturn(new QueryResponse(
                        "A", "2026-07-14T09:00:00Z", "BULLISH", 0.7, "r", List.of(), "RULE_BASED", "r", "m", true));

        InsightFeedResponse response = feedQuery.feed(OWNER);

        assertThat(response.insights()).hasSize(1);
        assertThat(response.insights().getFirst().groupId()).isEqualTo("G1");
    }

    @Test
    void ungroupedTickerLatestInsightAppearsWhenNoCoveringGroupInsightExists() {
        groupInsightsFor("C");
        when(insightQuery.findLatestInsight("C"))
                .thenReturn(new QueryResponse(
                        "C", "2026-07-14T08:00:00Z", "HOLD", 0.5, "r", List.of(), "RULE_BASED", "r", "m", true));

        InsightFeedResponse response = feedQuery.feed(OWNER);

        assertThat(response.found()).isTrue();
        assertThat(response.insights()).hasSize(1);
        FeedInsight insight = response.insights().getFirst();
        assertThat(insight.groupId()).isNull();
        assertThat(insight.tickers()).containsExactly("C");
        assertThat(insight.generatedAt()).isEqualTo("2026-07-14T08:00:00Z");
    }

    @Test
    void notFoundPerTickerInsightIsOmittedFromTheFeed() {
        groupInsightsFor("D");
        when(insightQuery.findLatestInsight("D")).thenReturn(QueryResponse.notFound("D"));

        InsightFeedResponse response = feedQuery.feed(OWNER);

        assertThat(response.found()).isFalse();
        assertThat(response.insights()).isEmpty();
    }

    @Test
    void sortsCombinedFeedNewestFirst() {
        List<String> tickers = List.of("A", "B");
        when(dynamoDb.query(any(QueryRequest.class))).thenAnswer(inv -> {
            QueryRequest request = inv.getArgument(0);
            if (request.indexName() == null) {
                return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(tickers.stream()
                                .map(t -> Map.of("ticker", str(t)))
                                .toList())
                        .build();
            }
            String pk = request.expressionAttributeValues().get(":pk").s();
            if (pk.equals("TICKER#A")) {
                return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(groupItem("G1", "2026-07-14T08:00:00Z", "A")))
                        .build();
            }
            return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                    .items(List.of())
                    .build();
        });
        when(insightQuery.findLatestInsight("A")).thenReturn(QueryResponse.notFound("A"));
        when(insightQuery.findLatestInsight("B"))
                .thenReturn(new QueryResponse(
                        "B", "2026-07-14T12:00:00Z", "HOLD", 0.5, "r", List.of(), "RULE_BASED", "r", "m", true));

        InsightFeedResponse response = feedQuery.feed(OWNER);

        assertThat(response.insights()).hasSize(2);
        assertThat(response.insights().get(0).generatedAt()).isEqualTo("2026-07-14T12:00:00Z");
        assertThat(response.insights().get(1).generatedAt()).isEqualTo("2026-07-14T08:00:00Z");
    }

    @Test
    void capsFeedAtTwentyFive() {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            items.add(Map.of("ticker", str("T" + i)));
        }
        List<String> tickers = items.stream().map(m -> m.get("ticker").s()).toList();
        when(dynamoDb.query(any(QueryRequest.class))).thenAnswer(inv -> {
            QueryRequest request = inv.getArgument(0);
            if (request.indexName() == null) {
                return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(items)
                        .build();
            }
            return software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                    .items(List.of())
                    .build();
        });
        for (int i = 0; i < tickers.size(); i++) {
            String ticker = tickers.get(i);
            when(insightQuery.findLatestInsight(ticker))
                    .thenReturn(new QueryResponse(
                            ticker,
                            String.format("2026-07-14T%02d:00:00Z", i % 24),
                            "HOLD",
                            0.5,
                            "r",
                            List.of(),
                            "RULE_BASED",
                            "r",
                            "m",
                            true));
        }

        InsightFeedResponse response = feedQuery.feed(OWNER);

        assertThat(response.insights()).hasSize(25);
    }

    @Test
    void queriesGsi1DescendingWithSmallLimitPerTicker() {
        groupInsightsFor("A");
        when(insightQuery.findLatestInsight("A")).thenReturn(QueryResponse.notFound("A"));

        feedQuery.feed(OWNER);

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb, atLeastOnce()).query(captor.capture());
        var gsi1Request = captor.getAllValues().stream()
                .filter(r -> r.indexName() != null)
                .findFirst()
                .orElseThrow();
        assertThat(gsi1Request.indexName()).isEqualTo("GSI1");
        assertThat(gsi1Request.scanIndexForward()).isFalse();
        assertThat(gsi1Request.limit()).isEqualTo(5);
        assertThat(gsi1Request.keyConditionExpression()).isEqualTo("GSI1PK = :pk");
        assertThat(gsi1Request.expressionAttributeValues()).containsValue(str("TICKER#A"));
    }

    @Test
    void watchlistReadQueriesOwnerPartitionForWatchItems() {
        watchlist("A");
        when(insightQuery.findLatestInsight("A")).thenReturn(QueryResponse.notFound("A"));

        feedQuery.feed(OWNER);

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).queryPaginator(captor.capture());
        assertThat(captor.getValue().expressionAttributeValues().get(":pk").s()).isEqualTo("USER#" + OWNER);
        assertThat(captor.getValue().expressionAttributeValues().get(":sk").s()).isEqualTo("WATCH#");
    }

    private static Map<String, AttributeValue> groupItem(String groupId, String generatedAt, String... tickers) {
        return Map.of(
                "groupId", str(groupId),
                "tickers", list(tickers),
                "generatedAt", str(generatedAt),
                "signal", str("BULLISH"),
                "confidence", num("0.75"),
                "rationale", str("correlated move"),
                "source", str("BEDROCK"));
    }

    private static AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue num(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static AttributeValue list(String... values) {
        return AttributeValue.builder()
                .l(java.util.Arrays.stream(values)
                        .map(InsightFeedQueryTest::str)
                        .toList())
                .build();
    }
}
