package dev.engnotes.insight.service;

import dev.engnotes.insight.exception.InsightException;
import dev.engnotes.insight.model.InsightResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Persists a generated insight to DynamoDB.
 *
 * Design: PK=ticker, SK=generatedAt (ISO-8601). "Latest insight for ticker" is a query on
 * PK=ticker, sort descending, Limit 1 (the query Lambda's read pattern). TTL expires rows
 * after 7 days, matching the InsightTable definition in FoundationStack; historical data
 * lives in the S3 lake. Flips the response's stored flag to true after a successful write.
 */
@Service
public class InsightStoreService {

    private static final Logger log = LoggerFactory.getLogger(InsightStoreService.class);

    // DynamoDB TTL: 7 days from now
    private static final long TTL_SECONDS = 7L * 24 * 60 * 60;

    private final DynamoDbClient dynamoDb;

    @Value("${INSIGHT_TABLE:financial-insights-dev}")
    private String insightTable;

    public InsightStoreService(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    public void store(InsightResponse insight) {
        long ttlEpoch = Instant.now().getEpochSecond() + TTL_SECONDS;

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("ticker", str(insight.getTicker()));
        item.put("generatedAt", str(insight.getGeneratedAt()));
        item.put("insightText", str(insight.getInsightText()));
        item.put("modelId", str(insight.getModelId()));
        item.put("promptVersion", str(insight.getPromptVersion()));
        item.put("ttl", num(String.valueOf(ttlEpoch)));
        // Structured contract (spec section 9): persisted so the read path can serve it.
        item.put("signal", str(insight.getSignal()));
        item.put("confidence", num(String.valueOf(insight.getConfidence())));
        item.put("rationale", str(insight.getRationale()));
        item.put("source", str(insight.getSource()));
        if (insight.getDrivers() != null && !insight.getDrivers().isEmpty()) {
            item.put("drivers", strList(insight.getDrivers()));
        }
        if (insight.getCorrelationId() != null) {
            item.put("correlationId", str(insight.getCorrelationId()));
        }

        try {
            dynamoDb.putItem(
                    PutItemRequest.builder().tableName(insightTable).item(item).build());
            insight.setStored(true);

            log.info(
                    "Insight stored. ticker={} generatedAt={} correlationId={}",
                    insight.getTicker(),
                    insight.getGeneratedAt(),
                    insight.getCorrelationId());

        } catch (Exception e) {
            log.error(
                    "Failed to store insight. ticker={} correlationId={} error={}",
                    insight.getTicker(),
                    insight.getCorrelationId(),
                    e.getMessage(),
                    e);
            throw new InsightException("Storage failed for insight on ticker " + insight.getTicker(), e);
        }
    }

    private AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue num(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private AttributeValue strList(List<String> values) {
        return AttributeValue.builder()
                .l(values.stream().map(this::str).toList())
                .build();
    }
}
