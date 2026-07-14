package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.exception.InsightException;
import dev.engnotes.insight.model.CorrelationEdge;
import dev.engnotes.insight.model.CorrelationGroup;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

@ExtendWith(MockitoExtension.class)
class CorrelationStoreServiceTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private CorrelationStoreService store;

    /** GROUP#{id} -> that group's stored INSIGHT#* items (LATEST, history, GSI1 mirrors). */
    private final Map<String, List<Map<String, AttributeValue>>> insightItemsByGroup = new HashMap<>();

    @BeforeEach
    void setUp() {
        store = new CorrelationStoreService(dynamoDb, TABLE);
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static Map<String, AttributeValue> insightItem(String groupId, String sk) {
        return Map.of("PK", s("GROUP#" + groupId), "SK", s(sk));
    }

    /**
     * Stubs the previous pass's state: a GROUPSET Query returning the previous group ids, a GetItem
     * per id returning that group's META item (members drive reverse-lookup cleanup), and per-group
     * INSIGHT#-prefixed Queries served from {@link #insightItemsByGroup} (empty unless a test
     * populates it).
     */
    private void stubPreviousPass(Map<String, List<String>> previousGroupIdsToMembers) {
        List<Map<String, AttributeValue>> groupSetItems = previousGroupIdsToMembers.keySet().stream()
                .map(id -> Map.of("PK", s("GROUPSET"), "SK", s("GROUP#" + id)))
                .toList();
        when(dynamoDb.query(any(QueryRequest.class))).thenAnswer(inv -> {
            QueryRequest request = inv.getArgument(0);
            Map<String, AttributeValue> values = request.expressionAttributeValues();
            if ("GROUPSET".equals(values.get(":pk").s())) {
                return QueryResponse.builder().items(groupSetItems).build();
            }
            AttributeValue skPrefix = values.get(":sk");
            if (skPrefix != null && "INSIGHT#".equals(skPrefix.s())) {
                String groupId = values.get(":pk").s().substring("GROUP#".length());
                return QueryResponse.builder()
                        .items(insightItemsByGroup.getOrDefault(groupId, List.of()))
                        .build();
            }
            return QueryResponse.builder().items(List.of()).build();
        });

        previousGroupIdsToMembers.forEach((id, members) -> when(dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(TABLE)
                        .key(Map.of("PK", s("GROUP#" + id), "SK", s("META")))
                        .build()))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "members",
                                AttributeValue.builder()
                                        .l(members.stream()
                                                .map(CorrelationStoreServiceTest::s)
                                                .toList())
                                        .build()))
                        .build()));
    }

    @Test
    void putsGroupMetaGroupSetMarkerAndReverseLookupsForEachMember() {
        stubPreviousPass(Map.of());
        CorrelationGroup group = new CorrelationGroup(
                "g1",
                List.of("RELIANCE.NS", "TCS.NS"),
                List.of(new CorrelationEdge("RELIANCE.NS", "TCS.NS", 0.72)),
                "30-point window",
                "2026-07-14T10:15:00Z");

        store.replaceAll(List.of(group));

        ArgumentCaptor<PutItemRequest> putCaptor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(4)).putItem(putCaptor.capture()); // GROUPSET marker + group META + 2 reverse lookups
        List<PutItemRequest> puts = putCaptor.getAllValues();

        PutItemRequest groupPut = puts.stream()
                .filter(p -> "GROUP#g1".equals(p.item().get("PK").s())
                        && "META".equals(p.item().get("SK").s()))
                .findFirst()
                .orElseThrow();
        assertThat(groupPut.item().get("members").l())
                .extracting(AttributeValue::s)
                .containsExactly("RELIANCE.NS", "TCS.NS");
        assertThat(groupPut.item().get("pairwiseRhos").l()).hasSize(1);
        assertThat(groupPut.item().get("window").s()).isEqualTo("30-point window");
        assertThat(groupPut.item().get("computedAt").s()).isEqualTo("2026-07-14T10:15:00Z");

        PutItemRequest groupSetPut = puts.stream()
                .filter(p -> "GROUPSET".equals(p.item().get("PK").s()))
                .findFirst()
                .orElseThrow();
        assertThat(groupSetPut.item().get("SK").s()).isEqualTo("GROUP#g1");
        assertThat(groupSetPut.item().get("groupId").s()).isEqualTo("g1");

        PutItemRequest reverseLookup = puts.stream()
                .filter(p -> "TICKER#RELIANCE.NS".equals(p.item().get("PK").s()))
                .findFirst()
                .orElseThrow();
        assertThat(reverseLookup.item().get("SK").s()).isEqualTo("GROUP");
        assertThat(reverseLookup.item().get("groupId").s()).isEqualTo("g1");
    }

    /**
     * The GROUPSET mirror must land before the META item: a crash between the two then leaves a
     * mirror pointing at a missing META (found and purged by the next pass) rather than an invisible
     * orphaned META no Query can reach.
     */
    @Test
    void writesGroupSetMirrorBeforeGroupMeta() {
        stubPreviousPass(Map.of());
        CorrelationGroup group =
                new CorrelationGroup("g1", List.of("RELIANCE.NS"), List.of(), "window", "2026-07-14T10:15:00Z");

        store.replaceAll(List.of(group));

        InOrder order = inOrder(dynamoDb);
        order.verify(dynamoDb).putItem(argThat((PutItemRequest p) -> "GROUPSET"
                .equals(p.item().get("PK").s())));
        order.verify(dynamoDb)
                .putItem(argThat((PutItemRequest p) ->
                        "GROUP#g1".equals(p.item().get("PK").s())
                                && "META".equals(p.item().get("SK").s())));
    }

    @Test
    void deletesGroupMetaAndGroupSetMarkerForAGroupNoLongerProducedByThisPass() {
        stubPreviousPass(Map.of("stale-group", List.of()));

        store.replaceAll(List.of());

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb, times(2)).deleteItem(captor.capture());
        List<DeleteItemRequest> deletes = captor.getAllValues();
        assertThat(deletes)
                .anySatisfy(d -> {
                    assertThat(d.key().get("PK").s()).isEqualTo("GROUP#stale-group");
                    assertThat(d.key().get("SK").s()).isEqualTo("META");
                })
                .anySatisfy(d -> {
                    assertThat(d.key().get("PK").s()).isEqualTo("GROUPSET");
                    assertThat(d.key().get("SK").s()).isEqualTo("GROUP#stale-group");
                });
    }

    /**
     * A departed group's INSIGHT# items (LATEST, history, per-member GSI1 mirrors) are purged with
     * the group, so its stale insights stop serving in the feed immediately instead of lingering
     * until their 7-day TTL.
     */
    @Test
    void purgesADepartedGroupsInsightItemsAlongWithItsMetaAndMarker() {
        stubPreviousPass(Map.of("old", List.of()));
        insightItemsByGroup.put(
                "old",
                List.of(
                        insightItem("old", "INSIGHT#LATEST"),
                        insightItem("old", "INSIGHT#2026-07-14T10:00:00Z"),
                        insightItem("old", "INSIGHT#2026-07-14T10:00:00Z#RELIANCE.NS")));

        store.replaceAll(List.of());

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb, times(5)).deleteItem(captor.capture()); // 3 INSIGHT# + META + GROUPSET marker
        List<String> deletedKeys = captor.getAllValues().stream()
                .map(d -> d.key().get("PK").s() + "/" + d.key().get("SK").s())
                .toList();
        assertThat(deletedKeys)
                .containsExactlyInAnyOrder(
                        "GROUP#old/INSIGHT#LATEST",
                        "GROUP#old/INSIGHT#2026-07-14T10:00:00Z",
                        "GROUP#old/INSIGHT#2026-07-14T10:00:00Z#RELIANCE.NS",
                        "GROUP#old/META",
                        "GROUPSET/GROUP#old");
    }

    @Test
    void deletesReverseLookupForATickerNoLongerInAnyGroup() {
        stubPreviousPass(Map.of("g1", List.of("RELIANCE.NS", "WIPRO.NS")));
        CorrelationGroup group =
                new CorrelationGroup("g1", List.of("RELIANCE.NS"), List.of(), "window", "2026-07-14T10:15:00Z");

        store.replaceAll(List.of(group));

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb).deleteItem(captor.capture());
        assertThat(captor.getValue().key().get("PK").s()).isEqualTo("TICKER#WIPRO.NS");
        assertThat(captor.getValue().key().get("SK").s()).isEqualTo("GROUP");
    }

    @Test
    void groupStillProducedByThisPassKeepsItsMetaAndInsightItems() {
        stubPreviousPass(Map.of("g1", List.of("RELIANCE.NS")));
        insightItemsByGroup.put("g1", List.of(insightItem("g1", "INSIGHT#LATEST")));
        CorrelationGroup group = new CorrelationGroup(
                "g1", List.of("RELIANCE.NS", "TCS.NS"), List.of(), "window", "2026-07-14T10:15:00Z");

        store.replaceAll(List.of(group));

        verify(dynamoDb, never()).deleteItem(any(DeleteItemRequest.class));
    }

    /**
     * Crash-recovery pin: a GROUPSET mirror whose META is missing (crash between the mirror put and
     * the META put) must not fail the pass - {@code groupMembers} treats a missing META as an empty
     * member list, and the stale-diff then purges the dangling mirror (and no-op deletes the META).
     */
    @Test
    void mirrorPointingAtMissingMetaIsToleratedAndPurged() {
        List<Map<String, AttributeValue>> groupSetItems = List.of(Map.of("PK", s("GROUPSET"), "SK", s("GROUP#ghost")));
        when(dynamoDb.query(any(QueryRequest.class))).thenAnswer(inv -> {
            QueryRequest request = inv.getArgument(0);
            if ("GROUPSET".equals(request.expressionAttributeValues().get(":pk").s())) {
                return QueryResponse.builder().items(groupSetItems).build();
            }
            return QueryResponse.builder().items(List.of()).build();
        });
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build()); // no META item

        assertThatCode(() -> store.replaceAll(List.of())).doesNotThrowAnyException();

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb, times(2)).deleteItem(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(d -> {
                    assertThat(d.key().get("PK").s()).isEqualTo("GROUP#ghost");
                    assertThat(d.key().get("SK").s()).isEqualTo("META");
                })
                .anySatisfy(d -> {
                    assertThat(d.key().get("PK").s()).isEqualTo("GROUPSET");
                    assertThat(d.key().get("SK").s()).isEqualTo("GROUP#ghost");
                });
    }

    @Test
    void wrapsDynamoFailuresInInsightException() {
        when(dynamoDb.query(any(QueryRequest.class))).thenThrow(new RuntimeException("dynamo down"));

        assertThatThrownBy(() -> store.replaceAll(List.of())).isInstanceOf(InsightException.class);
    }
}
