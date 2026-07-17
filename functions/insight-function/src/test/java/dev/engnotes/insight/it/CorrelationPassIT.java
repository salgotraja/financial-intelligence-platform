package dev.engnotes.insight.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.model.CorrelationRequest;
import dev.engnotes.insight.model.CorrelationResponse;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@SpringBootTest
class CorrelationPassIT extends AbstractLocalStackIT {

    // Wednesday 2026-07-15, 10:30 IST: inside the NSE session (same instant IngestionBeanIT pins).
    private static final Instant MARKET_OPEN_INSTANT = Instant.parse("2026-07-15T05:00:00Z");
    private static final Instant SERIES_START = Instant.parse("2026-07-15T04:30:00Z");

    // 12 one-minute buckets with alternating non-zero returns: enough variance for pearson and
    // enough aligned points (>= 10) to qualify.
    private static final double[] MOVING_PATTERN = {100, 102, 101, 104, 103, 106, 105, 108, 107, 110, 109, 112};

    @MockitoBean
    Clock clock;

    @Autowired
    Function<CorrelationRequest, CorrelationResponse> computeCorrelations;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private void seedWatchsetTicker(String ticker) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("WATCHSET").build(),
                        "SK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "ticker", AttributeValue.builder().s(ticker).build()))
                .build());
    }

    private void seedSeries(String ticker, double scale, double[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            String timestamp = SERIES_START.plus(i, ChronoUnit.MINUTES).toString();
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(PlatformSchema.PLATFORM_TABLE)
                    .item(Map.of(
                            "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                            "SK", AttributeValue.builder().s("TS#" + timestamp).build(),
                            "timestamp", AttributeValue.builder().s(timestamp).build(),
                            "price",
                                    AttributeValue.builder()
                                            .n(String.valueOf(pattern[i] * scale))
                                            .build()))
                    .build());
        }
    }

    private void seedFlatSeries(String ticker, double price, int points) {
        double[] flat = new double[points];
        java.util.Arrays.fill(flat, price);
        seedSeries(ticker, 1.0, flat);
    }

    private List<String> groupsetGroupIds() {
        return dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.builder().s("GROUPSET").build()))
                        .build())
                .items()
                .stream()
                .map(item -> item.get("SK").s().substring("GROUP#".length()))
                .toList();
    }

    private Map<String, AttributeValue> getItem(String pk, String sk) {
        return dynamoDbClient
                .getItem(GetItemRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(pk).build(),
                                "SK", AttributeValue.builder().s(sk).build()))
                        .build())
                .item();
    }

    @Test
    void correlationPassGroupsCorrelatedTickersAndWritesAllShapes() {
        when(clock.instant()).thenReturn(MARKET_OPEN_INSTANT);
        seedWatchsetTicker("INFY.NS");
        seedWatchsetTicker("TCS.NS");
        seedWatchsetTicker("RELIANCE.NS");
        seedSeries("INFY.NS", 1.0, MOVING_PATTERN);
        seedSeries("TCS.NS", 10.0, MOVING_PATTERN); // identical returns => rho = 1.0
        seedFlatSeries("RELIANCE.NS", 500.0, 12); // zero-variance returns => rho = 0.0

        CorrelationResponse response = computeCorrelations.apply(new CorrelationRequest("eventbridge-schedule"));

        assertThat(response.status()).isEqualTo("computed");
        assertThat(response.tickersEvaluated()).isEqualTo(3);
        assertThat(response.groupsComputed()).isEqualTo(1);

        List<String> groupIds = groupsetGroupIds();
        assertThat(groupIds).hasSize(1);
        String groupId = groupIds.getFirst();

        var meta = getItem("GROUP#" + groupId, "META");
        assertThat(meta.get("members").l().stream().map(AttributeValue::s))
                .containsExactly("INFY.NS", "TCS.NS"); // sorted ascending
        var rhos = meta.get("pairwiseRhos").l();
        assertThat(rhos).hasSize(1);
        assertThat(Double.parseDouble(rhos.getFirst().m().get("rho").n())).isGreaterThan(0.99);

        assertThat(getItem("TICKER#INFY.NS", "GROUP").get("groupId").s()).isEqualTo(groupId);
        assertThat(getItem("TICKER#TCS.NS", "GROUP").get("groupId").s()).isEqualTo(groupId);
        assertThat(getItem("TICKER#RELIANCE.NS", "GROUP")).isEmpty();
    }

    @Test
    void membershipChangePurgesStaleGroupAndItsInsights() {
        when(clock.instant()).thenReturn(MARKET_OPEN_INSTANT);
        seedWatchsetTicker("INFY.NS");
        seedWatchsetTicker("TCS.NS");
        seedSeries("INFY.NS", 1.0, MOVING_PATTERN);
        seedSeries("TCS.NS", 10.0, MOVING_PATTERN);

        computeCorrelations.apply(new CorrelationRequest("eventbridge-schedule"));
        String staleGroupId = groupsetGroupIds().getFirst();

        // A group insight hanging off the soon-to-be-stale group: the purge must take it too.
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK",
                                AttributeValue.builder()
                                        .s("GROUP#" + staleGroupId)
                                        .build(),
                        "SK", AttributeValue.builder().s("INSIGHT#LATEST").build(),
                        "generatedAt",
                                AttributeValue.builder()
                                        .s("2026-07-15T04:50:00Z")
                                        .build()))
                .build());

        // Third correlated ticker joins => membership changes => new groupId, old one is stale.
        seedWatchsetTicker("WIPRO.NS");
        seedSeries("WIPRO.NS", 5.0, MOVING_PATTERN);

        CorrelationResponse second = computeCorrelations.apply(new CorrelationRequest("eventbridge-schedule"));

        assertThat(second.groupsComputed()).isEqualTo(1);
        List<String> groupIds = groupsetGroupIds();
        assertThat(groupIds).hasSize(1);
        String newGroupId = groupIds.getFirst();
        assertThat(newGroupId).isNotEqualTo(staleGroupId);

        assertThat(getItem("GROUP#" + staleGroupId, "META")).isEmpty();
        assertThat(getItem("GROUP#" + staleGroupId, "INSIGHT#LATEST")).isEmpty();
        assertThat(getItem("GROUP#" + newGroupId, "META").get("members").l().stream()
                        .map(AttributeValue::s))
                .containsExactly("INFY.NS", "TCS.NS", "WIPRO.NS");
        assertThat(getItem("TICKER#INFY.NS", "GROUP").get("groupId").s()).isEqualTo(newGroupId);
    }

    @Test
    void marketClosedSkipsThePass() {
        // Same trading day, 16:30 IST: after the 15:35 IST close.
        when(clock.instant()).thenReturn(Instant.parse("2026-07-15T11:00:00Z"));

        CorrelationResponse response = computeCorrelations.apply(new CorrelationRequest("eventbridge-schedule"));

        assertThat(response.status()).isEqualTo("market-closed");
        assertThat(groupsetGroupIds()).isEmpty();
    }
}
