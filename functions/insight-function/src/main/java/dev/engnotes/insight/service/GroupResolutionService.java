package dev.engnotes.insight.service;

import dev.engnotes.insight.model.CorrelationEdge;
import dev.engnotes.insight.model.CorrelationGroup;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

/**
 * Resolves a ticker to its correlation group, the authoritative path for Task 7's cross-ticker
 * insight: {@code TICKER#{ticker}/GROUP} is the reverse lookup {@link CorrelationStoreService}
 * overwrites every correlation pass, and {@code GROUP#{groupId}/META} is the group it points at.
 *
 * <p>Orphan-tolerant: {@link CorrelationStoreService}'s stale-item cleanup is non-transactional, so
 * a reverse lookup can transiently point at a groupId whose META was already deleted (or not yet
 * written) by a concurrent pass. Both a missing reverse lookup and a reverse lookup whose META is
 * missing resolve to {@link Optional#empty()} - "no group" - so the caller falls back to per-ticker
 * insight behavior. A DynamoDB read failure is treated the same way: never let a control-plane read
 * block insight generation.
 */
@Service
public class GroupResolutionService {

    private static final Logger log = LoggerFactory.getLogger(GroupResolutionService.class);

    private static final String TICKER_PREFIX = "TICKER#";
    private static final String REVERSE_LOOKUP_SK = "GROUP";
    private static final String GROUP_PREFIX = "GROUP#";
    private static final String META_SK = "META";

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public GroupResolutionService(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    public Optional<CorrelationGroup> resolve(String ticker) {
        try {
            Map<String, AttributeValue> reverseLookup = getItem(TICKER_PREFIX + ticker, REVERSE_LOOKUP_SK);
            if (reverseLookup.isEmpty()) {
                return Optional.empty();
            }
            String groupId = attr(reverseLookup, "groupId");
            if (groupId == null) {
                log.warn("Reverse lookup has no groupId, falling back to per-ticker. ticker={}", ticker);
                return Optional.empty();
            }

            Map<String, AttributeValue> meta = getItem(GROUP_PREFIX + groupId, META_SK);
            if (meta.isEmpty()) {
                log.info(
                        "Group META missing for a resolved groupId (orphan tolerated), falling back to per-ticker. ticker={} groupId={}",
                        ticker,
                        groupId);
                return Optional.empty();
            }

            return Optional.of(toGroup(meta));
        } catch (Exception e) {
            log.warn("Group resolution failed, falling back to per-ticker. ticker={} error={}", ticker, e.toString());
            return Optional.empty();
        }
    }

    private Map<String, AttributeValue> getItem(String pk, String sk) {
        var response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", s(pk), "SK", s(sk)))
                .build());
        return response.hasItem() ? response.item() : Map.of();
    }

    private CorrelationGroup toGroup(Map<String, AttributeValue> meta) {
        return new CorrelationGroup(
                attr(meta, "groupId"),
                stringList(meta, "members"),
                edgeList(meta, "pairwiseRhos"),
                attr(meta, "window"),
                attr(meta, "computedAt"));
    }

    private List<String> stringList(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        if (value == null || !value.hasL()) {
            return List.of();
        }
        return value.l().stream().map(AttributeValue::s).toList();
    }

    private List<CorrelationEdge> edgeList(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        if (value == null || !value.hasL()) {
            return List.of();
        }
        return value.l().stream().map(this::toEdge).toList();
    }

    private CorrelationEdge toEdge(AttributeValue value) {
        Map<String, AttributeValue> m = value.m();
        return new CorrelationEdge(
                m.get("a").s(), m.get("b").s(), Double.parseDouble(m.get("rho").n()));
    }

    private static String attr(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(Objects.requireNonNull(value)).build();
    }
}
