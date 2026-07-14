package dev.engnotes.insight.service;

import dev.engnotes.insight.model.CorrelationGroup;
import dev.engnotes.insight.model.GroupInsightContext;
import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.MemberSnapshot;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Reads each group member's latest priced point and volume baseline, and assembles it with the
 * triggering ticker's anomaly and the group's pairwise correlations into a {@link
 * GroupInsightContext} for the Bedrock prompt and the rule-based cross-ticker fallback.
 *
 * <p>Per-member reads are best-effort: a read failure for one member logs a warning and yields a
 * snapshot of nulls rather than failing the whole group insight over one bad ticker.
 */
@Service
public class GroupContextReader {

    private static final Logger log = LoggerFactory.getLogger(GroupContextReader.class);

    private static final String TICKER_PREFIX = "TICKER#";
    private static final String TS_PREFIX = "TS#";
    private static final String BASELINE_SK = "BASELINE";

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public GroupContextReader(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    public GroupInsightContext buildContext(CorrelationGroup group, InsightRequest trigger) {
        var members = group.members().stream().map(this::readSnapshot).toList();
        return new GroupInsightContext(
                group.groupId(),
                group.members(),
                trigger.getTicker(),
                trigger.getAnomalyReason(),
                members,
                group.pairwiseRhos(),
                group.window());
    }

    private MemberSnapshot readSnapshot(String ticker) {
        try {
            Map<String, AttributeValue> latest = readLatestPoint(ticker);
            Map<String, AttributeValue> baseline = readBaseline(ticker);
            return new MemberSnapshot(
                    ticker,
                    decimal(latest, "price"),
                    decimal(latest, "changePercent"),
                    longValue(latest, "volume"),
                    number(baseline, "volumeMean"));
        } catch (Exception e) {
            log.warn("Failed to read group member snapshot, using nulls. ticker={} error={}", ticker, e.toString());
            return new MemberSnapshot(ticker, null, null, null, null);
        }
    }

    private Map<String, AttributeValue> readLatestPoint(String ticker) {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(
                        Map.of(":pk", AttributeValues.s(TICKER_PREFIX + ticker), ":sk", AttributeValues.s(TS_PREFIX)))
                .scanIndexForward(false)
                .limit(1)
                .build();
        var items = dynamoDb.query(request).items();
        return items.isEmpty() ? Map.of() : items.getFirst();
    }

    private Map<String, AttributeValue> readBaseline(String ticker) {
        var response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", AttributeValues.s(TICKER_PREFIX + ticker), "SK", AttributeValues.s(BASELINE_SK)))
                .build());
        return response.hasItem() ? response.item() : Map.of();
    }

    private static BigDecimal decimal(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? null : new BigDecimal(value.n());
    }

    private static Long longValue(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? null : Long.parseLong(value.n());
    }

    private static Double number(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? null : Double.parseDouble(value.n());
    }
}
