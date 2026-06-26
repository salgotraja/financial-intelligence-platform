package dev.engnotes.insight.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Bedrock cost tracking and the daily-spend circuit breaker (spec section 9).
 *
 * <p>Each billed invocation writes a per-call {@code COST#{yyyy-MM-dd} / INVOKE#...} item carrying
 * token counts and computed USD, and atomically increments the day's {@code COST#{yyyy-MM-dd} /
 * TOTAL} counter. {@link #isBreakerOpen()} reads that counter; once the day's spend reaches the cap,
 * the breaker opens and {@link BedrockInsightService} routes all insighting to the deterministic
 * {@link RuleBasedInsightGenerator} fallback until the UTC date rolls over (the partition key changes,
 * so the new day starts at zero with no cleanup).
 *
 * <p>Cost records share the insight table (PK/SK are strings, like the insight rows) and the role's
 * existing read/write grant, so no extra table or IAM is needed. The breaker read is eventually
 * consistent: a soft guardrail that may overshoot slightly under burst concurrency, which is the
 * correct trade for a test-window cost cap. A counter-read failure leaves the breaker closed so a
 * DynamoDB hiccup never blocks insight generation.
 */
@Service
public class CostTrackingService {

    private static final Logger log = LoggerFactory.getLogger(CostTrackingService.class);

    private static final BigDecimal PER_1K = BigDecimal.valueOf(1000);
    private static final int COST_SCALE = 8;
    private static final String TOTAL_SK = "TOTAL";
    // Cost records expire after 7 days, matching the insight TTL; daily aggregates are short-lived.
    private static final long TTL_SECONDS = 7L * 24 * 60 * 60;

    private final DynamoDbClient dynamoDb;
    private final String insightTable;
    private final BigDecimal dailyCapUsd;
    private final BigDecimal inputPricePer1k;
    private final BigDecimal outputPricePer1k;

    public CostTrackingService(
            DynamoDbClient dynamoDb,
            @Value("${INSIGHT_TABLE:financial-insights-dev}") String insightTable,
            @Value("${COST_DAILY_CAP_USD:5.0}") double dailyCapUsd,
            @Value("${BEDROCK_INPUT_PRICE_PER_1K:0.003}") double inputPricePer1k,
            @Value("${BEDROCK_OUTPUT_PRICE_PER_1K:0.015}") double outputPricePer1k) {
        this.dynamoDb = dynamoDb;
        this.insightTable = insightTable;
        this.dailyCapUsd = BigDecimal.valueOf(dailyCapUsd);
        this.inputPricePer1k = BigDecimal.valueOf(inputPricePer1k);
        this.outputPricePer1k = BigDecimal.valueOf(outputPricePer1k);
    }

    /** Blended USD cost for the given token usage at the configured per-1K prices. */
    public BigDecimal computeCost(long inputTokens, long outputTokens) {
        BigDecimal input = BigDecimal.valueOf(inputTokens).multiply(inputPricePer1k);
        BigDecimal output = BigDecimal.valueOf(outputTokens).multiply(outputPricePer1k);
        return input.add(output).divide(PER_1K, COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Records one billed Bedrock invocation: a per-call audit item plus an atomic add to the day's
     * spend counter. Tracking failures are logged and swallowed: cost accounting must never fail the
     * insight that was already produced.
     */
    public void record(String correlationId, long inputTokens, long outputTokens) {
        String dayKey = dayPartitionKey();
        BigDecimal cost = computeCost(inputTokens, outputTokens);
        try {
            writeInvocationItem(dayKey, correlationId, inputTokens, outputTokens, cost);
            incrementDailyTotal(dayKey, cost);
            log.info(
                    "Bedrock cost recorded. day={} inputTokens={} outputTokens={} costUsd={} correlationId={}",
                    dayKey,
                    inputTokens,
                    outputTokens,
                    cost.toPlainString(),
                    correlationId);
        } catch (Exception e) {
            log.error(
                    "Failed to record Bedrock cost. day={} costUsd={} correlationId={} error={}",
                    dayKey,
                    cost.toPlainString(),
                    correlationId,
                    e.toString());
        }
    }

    /** True when today's accumulated Bedrock spend has reached the configured daily cap. */
    public boolean isBreakerOpen() {
        String dayKey = dayPartitionKey();
        try {
            Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                            .tableName(insightTable)
                            .key(Map.of("ticker", str(dayKey), "generatedAt", str(TOTAL_SK)))
                            .projectionExpression("spendUsd")
                            .build())
                    .item();
            if (item == null || !item.containsKey("spendUsd")) {
                return false;
            }
            BigDecimal spend = new BigDecimal(item.get("spendUsd").n());
            boolean open = spend.compareTo(dailyCapUsd) >= 0;
            if (open) {
                log.warn(
                        "Bedrock cost circuit breaker OPEN. day={} spendUsd={} capUsd={}",
                        dayKey,
                        spend.toPlainString(),
                        dailyCapUsd.toPlainString());
            }
            return open;
        } catch (Exception e) {
            log.warn("Cost counter read failed, leaving breaker closed. day={} error={}", dayKey, e.toString());
            return false;
        }
    }

    private void writeInvocationItem(
            String dayKey, String correlationId, long inputTokens, long outputTokens, BigDecimal cost) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("ticker", str(dayKey));
        item.put("generatedAt", str("INVOKE#" + Instant.now() + "#" + System.nanoTime()));
        item.put("inputTokens", num(Long.toString(inputTokens)));
        item.put("outputTokens", num(Long.toString(outputTokens)));
        item.put("costUsd", num(cost.toPlainString()));
        item.put("ttl", num(Long.toString(Instant.now().getEpochSecond() + TTL_SECONDS)));
        if (correlationId != null) {
            item.put("correlationId", str(correlationId));
        }
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(insightTable).item(item).build());
    }

    private void incrementDailyTotal(String dayKey, BigDecimal cost) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(insightTable)
                .key(Map.of("ticker", str(dayKey), "generatedAt", str(TOTAL_SK)))
                .updateExpression("ADD spendUsd :delta SET #ttl = if_not_exists(#ttl, :ttl)")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .expressionAttributeValues(Map.of(
                        ":delta",
                        num(cost.toPlainString()),
                        ":ttl",
                        num(Long.toString(Instant.now().getEpochSecond() + TTL_SECONDS))))
                .build());
    }

    private String dayPartitionKey() {
        return "COST#" + LocalDate.now(ZoneOffset.UTC);
    }

    private AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue num(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
