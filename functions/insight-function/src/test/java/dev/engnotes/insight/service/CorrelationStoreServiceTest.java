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
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.paginators.ScanIterable;

@ExtendWith(MockitoExtension.class)
class CorrelationStoreServiceTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private CorrelationStoreService store;

    @BeforeEach
    void setUp() {
        store = new CorrelationStoreService(dynamoDb, TABLE);
        when(dynamoDb.scanPaginator(any(ScanRequest.class)))
                .thenAnswer(inv -> new ScanIterable(dynamoDb, inv.getArgument(0)));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private void stubExisting(List<String> existingGroupIds, List<String> existingReverseLookupTickers) {
        List<Map<String, AttributeValue>> groupItems = existingGroupIds.stream()
                .map(id -> Map.of("PK", s("GROUP#" + id), "SK", s("META")))
                .toList();
        List<Map<String, AttributeValue>> tickerItems = existingReverseLookupTickers.stream()
                .map(t -> Map.of("PK", s("TICKER#" + t), "SK", s("GROUP")))
                .toList();
        when(dynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().items(groupItems).build())
                .thenReturn(ScanResponse.builder().items(tickerItems).build());
    }

    @Test
    void putsGroupMetaAndReverseLookupsForEachMember() {
        stubExisting(List.of(), List.of());
        CorrelationGroup group = new CorrelationGroup(
                "g1",
                List.of("RELIANCE.NS", "TCS.NS"),
                List.of(new CorrelationEdge("RELIANCE.NS", "TCS.NS", 0.72)),
                "30-point window",
                "2026-07-14T10:15:00Z");

        store.replaceAll(List.of(group));

        ArgumentCaptor<PutItemRequest> putCaptor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(3)).putItem(putCaptor.capture()); // 1 group META + 2 reverse lookups
        List<PutItemRequest> puts = putCaptor.getAllValues();

        PutItemRequest groupPut = puts.stream()
                .filter(p -> "GROUP#g1".equals(p.item().get("PK").s()))
                .findFirst()
                .orElseThrow();
        assertThat(groupPut.item().get("SK").s()).isEqualTo("META");
        assertThat(groupPut.item().get("members").l())
                .extracting(AttributeValue::s)
                .containsExactly("RELIANCE.NS", "TCS.NS");
        assertThat(groupPut.item().get("pairwiseRhos").l()).hasSize(1);
        assertThat(groupPut.item().get("window").s()).isEqualTo("30-point window");
        assertThat(groupPut.item().get("computedAt").s()).isEqualTo("2026-07-14T10:15:00Z");

        PutItemRequest reverseLookup = puts.stream()
                .filter(p -> "TICKER#RELIANCE.NS".equals(p.item().get("PK").s()))
                .findFirst()
                .orElseThrow();
        assertThat(reverseLookup.item().get("SK").s()).isEqualTo("GROUP");
        assertThat(reverseLookup.item().get("groupId").s()).isEqualTo("g1");
    }

    @Test
    void deletesGroupNoLongerProducedByThisPass() {
        stubExisting(List.of("stale-group"), List.of());

        store.replaceAll(List.of());

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb).deleteItem(captor.capture());
        assertThat(captor.getValue().key().get("PK").s()).isEqualTo("GROUP#stale-group");
        assertThat(captor.getValue().key().get("SK").s()).isEqualTo("META");
    }

    @Test
    void deletesReverseLookupForATickerNoLongerInAnyGroup() {
        stubExisting(List.of(), List.of("WIPRO.NS"));

        store.replaceAll(List.of());

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb).deleteItem(captor.capture());
        assertThat(captor.getValue().key().get("PK").s()).isEqualTo("TICKER#WIPRO.NS");
        assertThat(captor.getValue().key().get("SK").s()).isEqualTo("GROUP");
    }

    @Test
    void groupStillProducedByThisPassIsNotDeleted() {
        stubExisting(List.of("g1"), List.of("RELIANCE.NS"));
        CorrelationGroup group = new CorrelationGroup(
                "g1", List.of("RELIANCE.NS", "TCS.NS"), List.of(), "window", "2026-07-14T10:15:00Z");

        store.replaceAll(List.of(group));

        verify(dynamoDb, never()).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void wrapsDynamoFailuresInInsightException() {
        when(dynamoDb.scan(any(ScanRequest.class))).thenThrow(new RuntimeException("dynamo down"));

        assertThatThrownBy(() -> store.replaceAll(List.of())).isInstanceOf(InsightException.class);
    }
}
