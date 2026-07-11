package dev.engnotes.query.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.query.model.QueryRequest;
import dev.engnotes.query.model.QueryResponse;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@SpringBootTest
class InsightQueryIT extends AbstractLocalStackIT {

    @Autowired
    Function<QueryRequest, QueryResponse> serveInsight;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private void seedInsight(String ticker, String generatedAt, String signal) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.ofEntries(
                        Map.entry(
                                "PK",
                                AttributeValue.builder().s("TICKER#" + ticker).build()),
                        Map.entry(
                                "SK",
                                AttributeValue.builder()
                                        .s("INSIGHT#" + generatedAt)
                                        .build()),
                        Map.entry(
                                "generatedAt",
                                AttributeValue.builder().s(generatedAt).build()),
                        Map.entry("signal", AttributeValue.builder().s(signal).build()),
                        Map.entry(
                                "confidence", AttributeValue.builder().n("0.80").build()),
                        Map.entry(
                                "rationale",
                                AttributeValue.builder().s("strong momentum").build()),
                        Map.entry(
                                "drivers",
                                AttributeValue.builder()
                                        .l(
                                                AttributeValue.builder()
                                                        .s("volume")
                                                        .build(),
                                                AttributeValue.builder()
                                                        .s("price")
                                                        .build())
                                        .build()),
                        Map.entry(
                                "source",
                                AttributeValue.builder().s("RULE_BASED").build()),
                        Map.entry(
                                "insightText",
                                AttributeValue.builder().s("BUY signal").build()),
                        Map.entry(
                                "modelId", AttributeValue.builder().s("rule-v1").build())))
                .build());
    }

    @Test
    void returnsLatestInsightForTicker() {
        seedInsight("TCS.NS", "2026-06-30T09:00:00Z", "HOLD");
        seedInsight("TCS.NS", "2026-06-30T10:00:00Z", "BUY");

        QueryResponse response = serveInsight.apply(new QueryRequest("TCS.NS", "corr-1"));

        assertThat(response.found()).isTrue();
        assertThat(response.signal()).isEqualTo("BUY"); // newest by SK descending
        assertThat(response.confidence()).isEqualTo(0.80);
        assertThat(response.drivers()).containsExactly("volume", "price");
    }

    @Test
    void returnsNotFoundWhenNoInsight() {
        QueryResponse response = serveInsight.apply(new QueryRequest("UNKNOWN.NS", "corr-2"));
        assertThat(response.found()).isFalse();
    }
}
