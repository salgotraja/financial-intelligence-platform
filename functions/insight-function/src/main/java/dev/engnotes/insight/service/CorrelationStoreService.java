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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Persists each pass's correlation groups and per-member reverse lookups, then removes whatever the
 * previous pass wrote that this one did not reproduce.
 *
 * <p>Design: {@code GROUP#{groupId}/META} holds the group; {@code TICKER#{ticker}/GROUP} is an
 * overwritten reverse lookup for O(1) group resolution from a ticker. A distinct-index item mirrors
 * WATCHSET: {@code GROUPSET / GROUP#{groupId}} is written alongside every group META item and deleted
 * alongside it, so the previous pass's group ids are a partition-key Query, never a Scan. Reverse-lookup
 * cleanup reads the previous pass's group META items (found via that same Query) for their member
 * lists, unions them, and diffs against this pass's members — again no Scan.
 *
 * <p>Deleting a stale group also purges its {@code GROUP#{id}/INSIGHT#*} items (LATEST, history, and
 * the per-member GSI1 mirrors {@code GroupInsightStoreService} wrote - all 7-day-TTL operational
 * data, not compliance data). Without this, a departed ticker's group mirror would keep serving in
 * the insight feed for up to the TTL after the group dissolved or changed membership.
 *
 * <p>Write order inside {@link #putGroup} is GROUPSET mirror first, then META: a crash between the
 * two leaves a mirror pointing at a missing META, which the next pass's {@link #queryGroupIds} sees
 * and {@link #deleteGroup} purges (self-cleaning; {@link #groupMembers} treats a missing META as an
 * empty member list). The reverse order would leave an invisible orphaned META no Query can find.
 *
 * <p>Migration note: groups written before this index existed have a META item but no GROUPSET mirror.
 * {@link GroupIdGenerator} ids are a hash of the sorted member list, so a group whose membership is
 * unchanged recomputes the same id and self-heals (its GROUPSET mirror is created the next time it is
 * put). A META item whose membership shape never recurs is never queried and is never cleaned up by
 * this pass; it is a bounded, cosmetic amount of dead storage at dev's watchlist scale and is
 * acceptable to leave, not worth a one-off Scan to purge.
 */
@Service
public class CorrelationStoreService {

    private static final Logger log = LoggerFactory.getLogger(CorrelationStoreService.class);

    private static final String GROUP_PREFIX = "GROUP#";
    private static final String META_SK = "META";
    private static final String TICKER_PREFIX = "TICKER#";
    private static final String REVERSE_LOOKUP_SK = "GROUP";
    private static final String GROUPSET_PK = "GROUPSET";
    private static final String INSIGHT_SK_PREFIX = "INSIGHT#";

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

            Set<String> previousGroupIds = queryGroupIds();
            Set<String> staleGroupIds = difference(previousGroupIds, newGroupIds);
            Set<String> staleReverseLookupTickers =
                    difference(previousMembers(previousGroupIds), newReverseLookups.keySet());

            groups.forEach(this::putGroup);
            newReverseLookups.forEach(this::putReverseLookup);
            staleGroupIds.forEach(this::deleteGroup);
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

    /** Group ids the previous pass produced, via the GROUPSET distinct-index partition (a Query). */
    private Set<String> queryGroupIds() {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk")
                .expressionAttributeValues(Map.of(":pk", s(GROUPSET_PK)))
                .build();
        return dynamoDb.queryPaginator(request).items().stream()
                .map(item -> item.get("SK"))
                .filter(Objects::nonNull)
                .map(AttributeValue::s)
                .map(sk -> sk.substring(GROUP_PREFIX.length()))
                .collect(Collectors.toSet());
    }

    /** Union of member tickers across the previous pass's groups, read from each group's META item. */
    private Set<String> previousMembers(Set<String> previousGroupIds) {
        return previousGroupIds.stream()
                .flatMap(groupId -> groupMembers(groupId).stream())
                .collect(Collectors.toSet());
    }

    private List<String> groupMembers(String groupId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", s(GROUP_PREFIX + groupId), "SK", s(META_SK)))
                .build());
        AttributeValue members =
                response.item() == null ? null : response.item().get("members");
        if (members == null || members.l() == null) {
            return List.of();
        }
        return members.l().stream().map(AttributeValue::s).toList();
    }

    private void putGroup(CorrelationGroup group) {
        // GROUPSET mirror first (see class javadoc): a crash after this put but before the META put
        // leaves a mirror pointing at a missing META, which the next pass finds and purges.
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(platformTable)
                .item(Map.of(
                        "PK", s(GROUPSET_PK),
                        "SK", s(GROUP_PREFIX + group.groupId()),
                        "groupId", s(group.groupId())))
                .build());
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

    private void deleteGroup(String groupId) {
        deleteInsightItems(groupId);
        deleteItem(GROUP_PREFIX + groupId, META_SK);
        deleteItem(GROUPSET_PK, GROUP_PREFIX + groupId);
    }

    /**
     * Purges a departed group's insight items (LATEST, history, per-member GSI1 mirrors - all
     * short-TTL operational data) so its stale insights stop serving in the feed the moment the
     * group dissolves or its membership changes, instead of lingering until TTL expiry.
     */
    private void deleteInsightItems(String groupId) {
        QueryRequest request = QueryRequest.builder()
                .tableName(platformTable)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                .expressionAttributeValues(Map.of(":pk", s(GROUP_PREFIX + groupId), ":sk", s(INSIGHT_SK_PREFIX)))
                .projectionExpression("PK, SK")
                .build();
        dynamoDb.queryPaginator(request).items().stream()
                .filter(item -> item.get("PK") != null && item.get("SK") != null)
                .forEach(item -> deleteItem(item.get("PK").s(), item.get("SK").s()));
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
