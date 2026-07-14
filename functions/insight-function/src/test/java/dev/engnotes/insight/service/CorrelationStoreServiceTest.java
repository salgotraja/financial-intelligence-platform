package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.exception.InsightException;
import dev.engnotes.insight.model.CorrelationEdge;
import dev.engnotes.insight.model.CorrelationGroup;
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

    @BeforeEach
    void setUp() {
        store = new CorrelationStoreService(dynamoDb, TABLE);
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    /**
     * Stubs the previous pass's state: a GROUPSET Query returning the previous group ids, and a
     * GetItem per id returning that group's META item (members drive reverse-lookup cleanup).
     */
    private void stubPreviousPass(Map<String, List<String>> previousGroupIdsToMembers) {
        List<Map<String, AttributeValue>> groupSetItems = previousGroupIdsToMembers.keySet().stream()
                .map(id -> Map.of("PK", s("GROUPSET"), "SK", s("GROUP#" + id)))
                .toList();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(groupSetItems).build());

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
        verify(dynamoDb, times(4)).putItem(putCaptor.capture()); // group META + GROUPSET marker + 2 reverse lookups
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
    void groupStillProducedByThisPassIsNotDeleted() {
        stubPreviousPass(Map.of("g1", List.of("RELIANCE.NS")));
        CorrelationGroup group = new CorrelationGroup(
                "g1", List.of("RELIANCE.NS", "TCS.NS"), List.of(), "window", "2026-07-14T10:15:00Z");

        store.replaceAll(List.of(group));

        verify(dynamoDb, never()).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void wrapsDynamoFailuresInInsightException() {
        when(dynamoDb.query(any(QueryRequest.class))).thenThrow(new RuntimeException("dynamo down"));

        assertThatThrownBy(() -> store.replaceAll(List.of())).isInstanceOf(InsightException.class);
    }
}
