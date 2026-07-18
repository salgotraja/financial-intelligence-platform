package dev.engnotes.query.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.query.model.InsightFeedRequest;
import dev.engnotes.query.model.InsightFeedResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@SpringBootTest
class InsightFeedQueryIT extends AbstractLocalStackIT {

    @Autowired
    Function<InsightFeedRequest, InsightFeedResponse> serveInsightFeed;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private void seedWatch(String sub, String ticker) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("WATCH#" + ticker).build(),
                        "ticker", AttributeValue.builder().s(ticker).build()))
                .build());
    }

    private void seedGroupMirror(String groupId, String member, List<String> tickers, String generatedAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.builder().s("GROUP#" + groupId).build());
        item.put(
                "SK",
                AttributeValue.builder()
                        .s("INSIGHT#" + generatedAt + "#" + member)
                        .build());
        item.put(
                PlatformSchema.GSI1_PK,
                AttributeValue.builder().s("TICKER#" + member).build());
        item.put(
                PlatformSchema.GSI1_SK,
                AttributeValue.builder().s("INSIGHT#" + generatedAt).build());
        item.put("groupId", AttributeValue.builder().s(groupId).build());
        item.put(
                "tickers",
                AttributeValue.builder()
                        .l(tickers.stream()
                                .map(t -> AttributeValue.builder().s(t).build())
                                .toList())
                        .build());
        item.put("generatedAt", AttributeValue.builder().s(generatedAt).build());
        item.put("signal", AttributeValue.builder().s("BULLISH").build());
        item.put("confidence", AttributeValue.builder().n("0.4").build());
        item.put("rationale", AttributeValue.builder().s("group momentum").build());
        item.put("source", AttributeValue.builder().s("RULE_BASED").build());
        item.put("member", AttributeValue.builder().s(member).build());
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(item)
                .build());
    }

    private void seedPerTickerInsight(String ticker, String generatedAt) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK",
                                AttributeValue.builder()
                                        .s("INSIGHT#" + generatedAt)
                                        .build(),
                        "generatedAt", AttributeValue.builder().s(generatedAt).build(),
                        "signal", AttributeValue.builder().s("NEUTRAL").build(),
                        "confidence", AttributeValue.builder().n("0.4").build(),
                        "rationale", AttributeValue.builder().s("flat session").build(),
                        "source", AttributeValue.builder().s("RULE_BASED").build()))
                .build());
    }

    @Test
    void feedDedupesGroupInsightsKeepingNewestGeneration() {
        seedWatch("owner-1", "INFY.NS");
        seedWatch("owner-1", "TCS.NS");
        List<String> members = List.of("INFY.NS", "TCS.NS");
        // Two generations of the same group, each mirrored per member: dedupe keeps the newest once.
        seedGroupMirror("g1", "INFY.NS", members, "2026-07-15T05:00:00Z");
        seedGroupMirror("g1", "TCS.NS", members, "2026-07-15T05:00:00Z");
        seedGroupMirror("g1", "INFY.NS", members, "2026-07-15T05:30:00Z");
        seedGroupMirror("g1", "TCS.NS", members, "2026-07-15T05:30:00Z");

        InsightFeedResponse response = serveInsightFeed.apply(new InsightFeedRequest("owner-1", "corr-f-1"));

        assertThat(response.found()).isTrue();
        assertThat(response.insights()).hasSize(1);
        assertThat(response.insights().getFirst().groupId()).isEqualTo("g1");
        assertThat(response.insights().getFirst().generatedAt()).isEqualTo("2026-07-15T05:30:00Z");
    }

    @Test
    void tickerNotCoveredByGroupFallsBackToPerTickerInsight() {
        seedWatch("owner-2", "INFY.NS");
        seedWatch("owner-2", "WIPRO.NS");
        seedGroupMirror("g2", "INFY.NS", List.of("INFY.NS", "TCS.NS"), "2026-07-15T05:00:00Z");
        seedPerTickerInsight("WIPRO.NS", "2026-07-15T04:45:00Z");

        InsightFeedResponse response = serveInsightFeed.apply(new InsightFeedRequest("owner-2", "corr-f-2"));

        assertThat(response.insights()).hasSize(2);
        // Sorted newest-first: the group insight (05:00) precedes the per-ticker one (04:45).
        assertThat(response.insights().getFirst().groupId()).isEqualTo("g2");
        var perTicker = response.insights().getLast();
        assertThat(perTicker.groupId()).isNull();
        assertThat(perTicker.tickers()).containsExactly("WIPRO.NS");
    }

    @Test
    void emptyWatchlistReturnsEmptyFeed() {
        InsightFeedResponse response = serveInsightFeed.apply(new InsightFeedRequest("owner-3", "corr-f-3"));

        assertThat(response.found()).isFalse();
        assertThat(response.insights()).isEmpty();
    }
}
