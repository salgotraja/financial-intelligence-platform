package dev.engnotes.insight.service;

import dev.engnotes.insight.exception.InsightException;
import dev.engnotes.insight.model.CorrelationEdge;
import dev.engnotes.insight.model.CorrelationGroup;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

/**
 * Persists each pass's correlation groups and per-member reverse lookups, then removes whatever the
 * previous pass wrote that this one did not reproduce.
 *
 * <p>Design: {@code GROUP#{groupId}/META} holds the group; {@code TICKER#{ticker}/GROUP} is an
 * overwritten reverse lookup for O(1) group resolution from a ticker. Neither key is reachable by a
 * targeted Query (no GSI indexes the GROUP# prefix or the reverse-lookup SK; the table's GSI1 is
 * reserved for the ticker-scoped insight feed, spec section 4), so the stale-item diff backs onto a
 * full-table Scan. At this table's size (a handful of watchlist tickers) a Scan every 15 minutes is
 * cheap; revisit with a dedicated GSI if the watchlist grows large enough to matter.
 */
@Service
public class CorrelationStoreService {

    private static final Logger log = LoggerFactory.getLogger(CorrelationStoreService.class);

    private static final String GROUP_PREFIX = "GROUP#";
    private static final String META_SK = "META";
    private static final String TICKER_PREFIX = "TICKER#";
    private static final String REVERSE_LOOKUP_SK = "GROUP";

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public CorrelationStoreService(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    public void replaceAll(List<CorrelationGroup> groups) {
        try {
            Map<String, String> newReverseLookups = groups.stream()
                    .flatMap(group -> group.members().stream().map(member -> Map.entry(member, group.groupId())))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            Set<String> newGroupIds =
                    groups.stream().map(CorrelationGroup::groupId).collect(Collectors.toSet());

            Set<String> staleGroupIds = difference(scanGroupIds(), newGroupIds);
            Set<String> staleReverseLookupTickers = difference(scanReverseLookupTickers(), newReverseLookups.keySet());

            groups.forEach(this::putGroup);
            newReverseLookups.forEach(this::putReverseLookup);
            staleGroupIds.forEach(groupId -> deleteItem(GROUP_PREFIX + groupId, META_SK));
            staleReverseLookupTickers.forEach(ticker -> deleteItem(TICKER_PREFIX + ticker, REVERSE_LOOKUP_SK));

            log.info(
                    "Correlation groups persisted. groups={} reverseLookups={} staleGroupsDeleted={} staleLookupsDeleted={}",
                    groups.size(),
                    newReverseLookups.size(),
                    staleGroupIds.size(),
                    staleReverseLookupTickers.size());
        } catch (Exception e) {
            log.error("Failed to persist correlation groups. error={}", e.getMessage(), e);
            throw new InsightException("Correlation group persistence failed", e);
        }
    }

    private static Set<String> difference(Set<String> existing, Set<String> current) {
        Set<String> result = new HashSet<>(existing);
        result.removeAll(current);
        return result;
    }

    private Set<String> scanGroupIds() {
        ScanRequest request = ScanRequest.builder()
                .tableName(platformTable)
                .filterExpression("begins_with(PK, :p) AND SK = :sk")
                .expressionAttributeValues(Map.of(":p", s(GROUP_PREFIX), ":sk", s(META_SK)))
                .build();
        return dynamoDb.scanPaginator(request).items().stream()
                .map(item -> item.get("PK"))
                .filter(Objects::nonNull)
                .map(AttributeValue::s)
                .map(pk -> pk.substring(GROUP_PREFIX.length()))
                .collect(Collectors.toSet());
    }

    private Set<String> scanReverseLookupTickers() {
        ScanRequest request = ScanRequest.builder()
                .tableName(platformTable)
                .filterExpression("begins_with(PK, :p) AND SK = :sk")
                .expressionAttributeValues(Map.of(":p", s(TICKER_PREFIX), ":sk", s(REVERSE_LOOKUP_SK)))
                .build();
        return dynamoDb.scanPaginator(request).items().stream()
                .map(item -> item.get("PK"))
                .filter(Objects::nonNull)
                .map(AttributeValue::s)
                .map(pk -> pk.substring(TICKER_PREFIX.length()))
                .collect(Collectors.toSet());
    }

    private void putGroup(CorrelationGroup group) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", s(GROUP_PREFIX + group.groupId()));
        item.put("SK", s(META_SK));
        item.put("groupId", s(group.groupId()));
        item.put("members", strList(group.members()));
        item.put("pairwiseRhos", rhoList(group.pairwiseRhos()));
        item.put("window", s(group.window()));
        item.put("computedAt", s(group.computedAt()));
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(platformTable).item(item).build());
    }

    private void putReverseLookup(String ticker, String groupId) {
        Map<String, AttributeValue> item = Map.of(
                "PK", s(TICKER_PREFIX + ticker),
                "SK", s(REVERSE_LOOKUP_SK),
                "ticker", s(ticker),
                "groupId", s(groupId));
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(platformTable).item(item).build());
    }

    private void deleteItem(String pk, String sk) {
        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", s(pk), "SK", s(sk)))
                .build());
    }

    private static AttributeValue strList(List<String> values) {
        return AttributeValue.builder()
                .l(values.stream().map(CorrelationStoreService::s).toList())
                .build();
    }

    private static AttributeValue rhoList(List<CorrelationEdge> edges) {
        return AttributeValue.builder()
                .l(edges.stream().map(CorrelationStoreService::rhoMap).toList())
                .build();
    }

    private static AttributeValue rhoMap(CorrelationEdge edge) {
        return AttributeValue.builder()
                .m(Map.of(
                        "a", s(edge.tickerA()),
                        "b", s(edge.tickerB()),
                        "rho", n(edge.rho())))
                .build();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(double value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }
}
