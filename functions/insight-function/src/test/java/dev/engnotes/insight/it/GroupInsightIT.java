package dev.engnotes.insight.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.InsightResponse;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@SpringBootTest
class GroupInsightIT extends AbstractLocalStackIT {

    private static final String GROUP_ID = "cafebabe12345678";
    private static final List<String> MEMBERS = List.of("INFY.NS", "TCS.NS");

    // Any invokeModel call fails => BedrockInsightService's real RULE_BASED fallback executes.
    @MockitoBean
    BedrockRuntimeClient bedrockRuntimeClient;

    @Autowired
    Function<InsightRequest, InsightResponse> generateInsight;

    @Autowired
    DynamoDbClient dynamoDbClient;

    @BeforeEach
    void stubBedrockUnavailable() {
        when(bedrockRuntimeClient.invokeModel(any(InvokeModelRequest.class)))
                .thenThrow(SdkClientException.create("Bedrock disabled in ITs"));
    }

    private void seedGroup() {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("GROUP#" + GROUP_ID).build(),
                        "SK", AttributeValue.builder().s("META").build(),
                        "groupId", AttributeValue.builder().s(GROUP_ID).build(),
                        "members",
                                AttributeValue.builder()
                                        .l(MEMBERS.stream()
                                                .map(m -> AttributeValue.builder()
                                                        .s(m)
                                                        .build())
                                                .toList())
                                        .build(),
                        "pairwiseRhos",
                                AttributeValue.builder()
                                        .l(AttributeValue.builder()
                                                .m(Map.of(
                                                        "a",
                                                                AttributeValue.builder()
                                                                        .s("INFY.NS")
                                                                        .build(),
                                                        "b",
                                                                AttributeValue.builder()
                                                                        .s("TCS.NS")
                                                                        .build(),
                                                        "rho",
                                                                AttributeValue.builder()
                                                                        .n("0.92")
                                                                        .build()))
                                                .build())
                                        .build(),
                        "window", AttributeValue.builder().s("test-window").build(),
                        "computedAt",
                                AttributeValue.builder()
                                        .s("2026-07-15T04:45:00Z")
                                        .build()))
                .build());
        for (String member : MEMBERS) {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(PlatformSchema.PLATFORM_TABLE)
                    .item(Map.of(
                            "PK", AttributeValue.builder().s("TICKER#" + member).build(),
                            "SK", AttributeValue.builder().s("GROUP").build(),
                            "ticker", AttributeValue.builder().s(member).build(),
                            "groupId", AttributeValue.builder().s(GROUP_ID).build()))
                    .build());
            seedLatestPoint(member);
        }
    }

    private void seedLatestPoint(String ticker) {
        String timestamp = "2026-07-15T04:59:00Z";
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK", AttributeValue.builder().s("TS#" + timestamp).build(),
                        "timestamp", AttributeValue.builder().s(timestamp).build(),
                        "price", AttributeValue.builder().n("150.00").build(),
                        "changePercent", AttributeValue.builder().n("2.0").build(),
                        "volume", AttributeValue.builder().n("2000").build()))
                .build());
    }

    private InsightRequest request(String ticker) {
        InsightRequest request = new InsightRequest();
        request.setTicker(ticker);
        request.setCorrelationId("corr-gi-1");
        request.setPrice(new BigDecimal("150.00"));
        request.setChangePercent(new BigDecimal("2.0"));
        request.setVolume(2000L);
        request.setAnomaly(true);
        request.setAnomalyReason("volume spike 3.1x baseline");
        return request;
    }

    private List<Map<String, AttributeValue>> queryPrefix(String pk, String skPrefix) {
        return dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.builder().s(pk).build(),
                                ":sk", AttributeValue.builder().s(skPrefix).build()))
                        .build())
                .items();
    }

    @Test
    void groupInsightWritesLatestHistoryMirrorsAndMemberInsights() {
        seedGroup();

        InsightResponse response = generateInsight.apply(request("INFY.NS"));

        assertThat(response.isSkipped()).isFalse();
        assertThat(response.getSource()).isEqualTo("RULE_BASED");
        assertThat(response.getSignal()).isEqualTo("BULLISH"); // both members at +2% >= 1% threshold
        assertThat(response.getConfidence()).isEqualTo(0.4);

        // INSIGHT#LATEST + INSIGHT#{iso} history + one INSIGHT#{iso}#{member} mirror per member = 4.
        var groupItems = queryPrefix("GROUP#" + GROUP_ID, "INSIGHT#");
        assertThat(groupItems).hasSize(4);

        var latest = queryPrefix("GROUP#" + GROUP_ID, "INSIGHT#LATEST");
        assertThat(latest).hasSize(1);
        assertThat(latest.getFirst().get("signal").s()).isEqualTo("BULLISH");
        assertThat(latest.getFirst().get("source").s()).isEqualTo("RULE_BASED");

        // GSI1 mirror per member, queryable by ticker.
        for (String member : MEMBERS) {
            var mirrors = dynamoDbClient
                    .query(QueryRequest.builder()
                            .tableName(PlatformSchema.PLATFORM_TABLE)
                            .indexName(PlatformSchema.GSI1_NAME)
                            .keyConditionExpression("GSI1PK = :pk")
                            .expressionAttributeValues(Map.of(
                                    ":pk",
                                    AttributeValue.builder()
                                            .s("TICKER#" + member)
                                            .build()))
                            .build())
                    .items();
            assertThat(mirrors).hasSize(1);
            assertThat(mirrors.getFirst().get("groupId").s()).isEqualTo(GROUP_ID);
        }

        // Per-member per-ticker insight fan-out (dashboard read path).
        for (String member : MEMBERS) {
            assertThat(queryPrefix("TICKER#" + member, "INSIGHT#")).hasSize(1);
        }
    }

    @Test
    void secondInvocationWithinIntervalSkips() {
        seedGroup();

        generateInsight.apply(request("INFY.NS"));
        InsightResponse second = generateInsight.apply(request("TCS.NS"));

        assertThat(second.isSkipped()).isTrue();
        assertThat(second.isStored()).isFalse();
        // Anti-spam: no second generation landed - still exactly 4 group items.
        assertThat(queryPrefix("GROUP#" + GROUP_ID, "INSIGHT#")).hasSize(4);
    }

    @Test
    void noGroupFallsThroughToPerTickerInsight() {
        InsightResponse response = generateInsight.apply(request("WIPRO.NS"));

        assertThat(response.isSkipped()).isFalse();
        assertThat(response.getSource()).isEqualTo("RULE_BASED");
        assertThat(response.isStored()).isTrue();
        assertThat(queryPrefix("TICKER#WIPRO.NS", "INSIGHT#")).hasSize(1);
        // No group artifacts of any kind.
        assertThat(dynamoDbClient
                        .query(QueryRequest.builder()
                                .tableName(PlatformSchema.PLATFORM_TABLE)
                                .keyConditionExpression("PK = :pk")
                                .expressionAttributeValues(Map.of(
                                        ":pk",
                                        AttributeValue.builder().s("GROUPSET").build()))
                                .build())
                        .items())
                .isEmpty();
    }
}
