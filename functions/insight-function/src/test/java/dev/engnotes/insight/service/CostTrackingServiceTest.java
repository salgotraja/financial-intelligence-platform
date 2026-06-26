package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@ExtendWith(MockitoExtension.class)
class CostTrackingServiceTest {

    private static final String TABLE = "financial-insights-test";
    private static final double CAP_USD = 5.0;
    private static final double INPUT_PRICE_PER_1K = 0.003;
    private static final double OUTPUT_PRICE_PER_1K = 0.015;

    @Mock
    private DynamoDbClient dynamoDb;

    private CostTrackingService service;

    @BeforeEach
    void setUp() {
        service = new CostTrackingService(dynamoDb, TABLE, CAP_USD, INPUT_PRICE_PER_1K, OUTPUT_PRICE_PER_1K);
    }

    @Test
    void computeCostBlendsInputAndOutputPricing() {
        // 1000 input * 0.003/1k + 1000 output * 0.015/1k = 0.003 + 0.015 = 0.018
        assertThat(service.computeCost(1000, 1000)).isEqualByComparingTo("0.018");
        assertThat(service.computeCost(0, 0)).isEqualByComparingTo("0");
        assertThat(service.computeCost(2000, 500)).isEqualByComparingTo("0.0135");
    }

    @Test
    void recordWritesPerInvocationItemAndAtomicallyIncrementsDailyTotal() {
        service.record("corr-42", 1000, 1000);

        ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(put.capture());
        Map<String, AttributeValue> item = put.getValue().item();
        assertThat(put.getValue().tableName()).isEqualTo(TABLE);
        assertThat(item.get("ticker").s()).startsWith("COST#");
        assertThat(item.get("generatedAt").s()).startsWith("INVOKE#");
        assertThat(item.get("inputTokens").n()).isEqualTo("1000");
        assertThat(item.get("outputTokens").n()).isEqualTo("1000");
        assertThat(new BigDecimal(item.get("costUsd").n())).isEqualByComparingTo("0.018");
        assertThat(item.get("correlationId").s()).isEqualTo("corr-42");
        assertThat(item).containsKey("ttl");

        ArgumentCaptor<UpdateItemRequest> update = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(update.capture());
        UpdateItemRequest req = update.getValue();
        assertThat(req.tableName()).isEqualTo(TABLE);
        assertThat(req.key().get("ticker").s()).startsWith("COST#");
        assertThat(req.key().get("generatedAt").s()).isEqualTo("TOTAL");
        assertThat(req.updateExpression()).contains("ADD");
        assertThat(new BigDecimal(req.expressionAttributeValues().get(":delta").n()))
                .isEqualByComparingTo("0.018");
    }

    @Test
    void breakerOpenWhenDailySpendReachesCap() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "spendUsd", AttributeValue.builder().n("5.0").build()))
                        .build());

        assertThat(service.isBreakerOpen()).isTrue();
    }

    @Test
    void breakerClosedWhenDailySpendBelowCap() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "spendUsd", AttributeValue.builder().n("4.99").build()))
                        .build());

        assertThat(service.isBreakerOpen()).isFalse();
    }

    @Test
    void breakerClosedWhenNoSpendRecordedYet() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertThat(service.isBreakerOpen()).isFalse();
    }

    @Test
    void breakerStaysClosedWhenCounterReadErrors() {
        // A cost-counter read failure must not block insight generation: allow Bedrock.
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenThrow(new RuntimeException("dynamo down"));

        assertThat(service.isBreakerOpen()).isFalse();
    }
}
