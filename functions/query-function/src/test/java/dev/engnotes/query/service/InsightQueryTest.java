package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.query.model.QueryResponse;
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

@ExtendWith(MockitoExtension.class)
class InsightQueryTest {

    private static final String TABLE = "financial-insights-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private InsightQuery insightQuery;

    @BeforeEach
    void setUp() {
        insightQuery = new InsightQuery(dynamoDb, TABLE);
    }

    @Test
    void returnsLatestInsightWhenItemExists() {
        Map<String, AttributeValue> item = Map.of(
                "ticker", str("RELIANCE.NS"),
                "generatedAt", str("2026-06-26T10:00:00Z"),
                "insightText", str("Momentum is positive on above-average volume."),
                "modelId", str("global.anthropic.claude-sonnet-4-5-20250929-v1:0"),
                "signal", str("BULLISH"),
                "confidence", num("0.82"),
                "rationale", str("Momentum is positive on above-average volume."),
                "drivers", list("change percent: 3.2", "volume spike"),
                "source", str("BEDROCK"));
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of(item))
                        .build());

        QueryResponse response = insightQuery.findLatestInsight("RELIANCE.NS");

        assertThat(response.found()).isTrue();
        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
        assertThat(response.generatedAt()).isEqualTo("2026-06-26T10:00:00Z");
        assertThat(response.signal()).isEqualTo("BULLISH");
        assertThat(response.confidence()).isEqualTo(0.82);
        assertThat(response.rationale()).isEqualTo("Momentum is positive on above-average volume.");
        assertThat(response.drivers()).containsExactly("change percent: 3.2", "volume spike");
        assertThat(response.source()).isEqualTo("BEDROCK");
        assertThat(response.insightText()).isEqualTo("Momentum is positive on above-average volume.");
        assertThat(response.modelId()).isEqualTo("global.anthropic.claude-sonnet-4-5-20250929-v1:0");
    }

    @Test
    void queriesLatestItemDescendingWithLimitOne() {
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of())
                        .build());

        insightQuery.findLatestInsight("TCS.NS");

        ArgumentCaptor<software.amazon.awssdk.services.dynamodb.model.QueryRequest> captor =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class);
        verify(dynamoDb).query(captor.capture());
        var sent = captor.getValue();
        assertThat(sent.tableName()).isEqualTo(TABLE);
        assertThat(sent.scanIndexForward()).isFalse();
        assertThat(sent.limit()).isEqualTo(1);
        assertThat(sent.keyConditionExpression()).isEqualTo("PK = :pk AND begins_with(SK, :sk)");
        assertThat(sent.expressionAttributeValues()).containsValue(str("TICKER#TCS.NS"));
        assertThat(sent.expressionAttributeValues()).containsValue(str("INSIGHT#"));
    }

    @Test
    void returnsNotFoundWhenNoInsightExists() {
        when(dynamoDb.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.QueryResponse.builder()
                        .items(List.of())
                        .build());

        QueryResponse response = insightQuery.findLatestInsight("INFY.NS");

        assertThat(response.found()).isFalse();
        assertThat(response.ticker()).isEqualTo("INFY.NS");
        assertThat(response.insightText()).isNull();
        assertThat(response.generatedAt()).isNull();
        assertThat(response.signal()).isNull();
        assertThat(response.confidence()).isZero();
        assertThat(response.drivers()).isEmpty();
    }

    @Test
    void rejectsBlankTickerWithoutTouchingDynamoDb() {
        assertThatThrownBy(() -> insightQuery.findLatestInsight("  ")).isInstanceOf(IllegalArgumentException.class);
        verify(dynamoDb, never()).query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class));
    }

    @Test
    void rejectsMalformedTickerWithoutTouchingDynamoDb() {
        assertThatThrownBy(() -> insightQuery.findLatestInsight("DROP TABLE; --"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dynamoDb, never()).query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class));
    }

    private static AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue num(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static AttributeValue list(String... values) {
        return AttributeValue.builder()
                .l(java.util.Arrays.stream(values).map(InsightQueryTest::str).toList())
                .build();
    }
}
