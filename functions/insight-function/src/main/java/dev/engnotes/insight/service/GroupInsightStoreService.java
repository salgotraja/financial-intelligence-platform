package dev.engnotes.insight.service;

import dev.engnotes.insight.exception.InsightException;
import dev.engnotes.insight.model.GroupInsightResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Persists a generated group insight (Task 7): {@code GROUP#{groupId}/INSIGHT#LATEST} overwritten
 * for the anti-spam check, {@code GROUP#{groupId}/INSIGHT#{iso8601}} as 7-day TTL history, and one
 * GSI1 mirror item per member ticker ({@code GSI1PK=TICKER#{member}}, {@code
 * GSI1SK=INSIGHT#{iso8601}}) so a future per-user feed can query group insights by ticker without
 * scanning every group (DataStack's GSI1 was reserved for exactly this). The mirror's own table key
 * is {@code GROUP#{groupId}/INSIGHT#{iso8601}#{member}}, distinct from the canonical history item so
 * the two writes never collide.
 *
 * <p>The existing per-ticker {@code TICKER#{ticker}/INSIGHT#} write is untouched by this class -
 * {@code InsightGenerationService} reuses {@link InsightStoreService} directly for each member so
 * that surface keeps its current schema and DynamoDB Streams notification behavior unchanged.
 */
@Service
public class GroupInsightStoreService {

    private static final Logger log = LoggerFactory.getLogger(GroupInsightStoreService.class);

    private static final String GROUP_PREFIX = "GROUP#";
    private static final String TICKER_PREFIX = "TICKER#";
    private static final String LATEST_SK = "INSIGHT#LATEST";
    private static final String INSIGHT_SK_PREFIX = "INSIGHT#";
    private static final long TTL_SECONDS = 7L * 24 * 60 * 60;

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public GroupInsightStoreService(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    /**
     * The group's last generation time, or empty when there is none or the read fails - a read
     * failure must never block insight generation, so it is treated the same as "no prior insight".
     */
    public Optional<Instant> latestGeneratedAt(String groupId) {
        try {
            var response = dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(platformTable)
                    .key(Map.of("PK", AttributeValues.s(GROUP_PREFIX + groupId), "SK", AttributeValues.s(LATEST_SK)))
                    .build());
            if (!response.hasItem()) {
                return Optional.empty();
            }
            AttributeValue generatedAt = response.item().get("generatedAt");
            if (generatedAt == null || generatedAt.s() == null) {
                return Optional.empty();
            }
            return Optional.of(Instant.parse(generatedAt.s()));
        } catch (DateTimeParseException e) {
            log.warn(
                    "Group INSIGHT#LATEST has an unparseable generatedAt, treating as no prior insight. groupId={}",
                    groupId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn(
                    "Failed to read group INSIGHT#LATEST, treating as no prior insight. groupId={} error={}",
                    groupId,
                    e.toString());
            return Optional.empty();
        }
    }

    public void store(GroupInsightResponse insight) {
        try {
            Map<String, AttributeValue> item = buildItem(insight);

            putLatest(insight.groupId(), item);
            putHistory(insight.groupId(), insight.generatedAt(), item);
            insight.tickers().forEach(member -> putMirror(insight, member, item));

            log.info(
                    "Group insight stored. groupId={} tickers={} source={} correlationId={}",
                    insight.groupId(),
                    insight.tickers(),
                    insight.source(),
                    insight.correlationId());
        } catch (Exception e) {
            log.error("Failed to store group insight. groupId={} error={}", insight.groupId(), e.getMessage(), e);
            throw new InsightException("Storage failed for group insight " + insight.groupId(), e);
        }
    }

    private void putLatest(String groupId, Map<String, AttributeValue> item) {
        Map<String, AttributeValue> latest = new HashMap<>(item);
        latest.put("PK", AttributeValues.s(GROUP_PREFIX + groupId));
        latest.put("SK", AttributeValues.s(LATEST_SK));
        latest.put("ttl", ttl());
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(platformTable).item(latest).build());
    }

    private void putHistory(String groupId, String generatedAt, Map<String, AttributeValue> item) {
        Map<String, AttributeValue> history = new HashMap<>(item);
        history.put("PK", AttributeValues.s(GROUP_PREFIX + groupId));
        history.put("SK", AttributeValues.s(INSIGHT_SK_PREFIX + generatedAt));
        history.put("ttl", ttl());
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(platformTable).item(history).build());
    }

    private void putMirror(GroupInsightResponse insight, String member, Map<String, AttributeValue> item) {
        Map<String, AttributeValue> mirror = new HashMap<>(item);
        mirror.put("PK", AttributeValues.s(GROUP_PREFIX + insight.groupId()));
        mirror.put("SK", AttributeValues.s(INSIGHT_SK_PREFIX + insight.generatedAt() + "#" + member));
        mirror.put("GSI1PK", AttributeValues.s(TICKER_PREFIX + member));
        mirror.put("GSI1SK", AttributeValues.s(INSIGHT_SK_PREFIX + insight.generatedAt()));
        mirror.put("member", AttributeValues.s(member));
        mirror.put("ttl", ttl());
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(platformTable).item(mirror).build());
    }

    private Map<String, AttributeValue> buildItem(GroupInsightResponse insight) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("groupId", AttributeValues.s(insight.groupId()));
        item.put("tickers", strList(insight.tickers()));
        item.put("generatedAt", AttributeValues.s(insight.generatedAt()));
        item.put("signal", AttributeValues.s(insight.signal()));
        item.put("confidence", n(String.valueOf(insight.confidence())));
        item.put("rationale", AttributeValues.s(insight.rationale()));
        item.put("source", AttributeValues.s(insight.source()));
        item.put("modelId", AttributeValues.s(insight.modelId()));
        item.put("promptVersion", AttributeValues.s(insight.promptVersion()));
        if (insight.drivers() != null && !insight.drivers().isEmpty()) {
            item.put("drivers", strList(insight.drivers()));
        }
        if (insight.correlationId() != null) {
            item.put("correlationId", AttributeValues.s(insight.correlationId()));
        }
        return item;
    }

    private AttributeValue ttl() {
        return n(String.valueOf(Instant.now().getEpochSecond() + TTL_SECONDS));
    }

    private AttributeValue n(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private AttributeValue strList(List<String> values) {
        return AttributeValue.builder()
                .l(values.stream().map(AttributeValues::s).toList())
                .build();
    }
}
