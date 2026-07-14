package dev.engnotes.notifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

@ExtendWith(MockitoExtension.class)
class ConnectionRegistryTest {

    private static final String TABLE = "financial-connections-test";
    private static final String PLATFORM_TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private ConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ConnectionRegistry(dynamoDb, TABLE, PLATFORM_TABLE);
    }

    @Test
    void subscribeWritesOneRowPerTickerWithFutureTtl() {
        int count = registry.subscribe("conn-1", List.of("RELIANCE.NS", "TCS.NS"));

        assertThat(count).isEqualTo(2);
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, org.mockito.Mockito.times(2)).putItem(captor.capture());
        var first = captor.getAllValues().getFirst();
        assertThat(first.tableName()).isEqualTo(TABLE);
        assertThat(first.item().get("ticker").s()).isEqualTo("RELIANCE.NS");
        assertThat(first.item().get("connectionId").s()).isEqualTo("conn-1");
        long ttl = Long.parseLong(first.item().get("ttl").n());
        assertThat(ttl).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void subscribeRejectsMalformedTickerWithoutTouchingDynamoDb() {
        assertThatThrownBy(() -> registry.subscribe("conn-1", List.of("bad ticker")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid ticker:");
        verify(dynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void subscribeRejectsOversizedTickerListWithoutTouchingDynamoDb() {
        List<String> tickers = java.util.stream.IntStream.rangeClosed(1, 26)
                .mapToObj(i -> "T" + i)
                .toList();

        assertThatThrownBy(() -> registry.subscribe("conn-1", tickers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Too many tickers:");
        verify(dynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void disconnectQueriesGsiAndDeletesEachRow() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(
                                Map.of(
                                        "ticker",
                                                AttributeValue.builder()
                                                        .s("RELIANCE.NS")
                                                        .build(),
                                        "connectionId",
                                                AttributeValue.builder()
                                                        .s("conn-1")
                                                        .build()),
                                Map.of(
                                        "ticker",
                                                AttributeValue.builder()
                                                        .s("TCS.NS")
                                                        .build(),
                                        "connectionId",
                                                AttributeValue.builder()
                                                        .s("conn-1")
                                                        .build())))
                        .build());

        int count = registry.disconnect("conn-1");

        assertThat(count).isEqualTo(2);
        ArgumentCaptor<QueryRequest> queryCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(queryCaptor.capture());
        assertThat(queryCaptor.getValue().indexName()).isEqualTo("by-connection");
        assertThat(queryCaptor.getValue().expressionAttributeValues())
                .containsEntry(":cid", AttributeValue.builder().s("conn-1").build());
        ArgumentCaptor<DeleteItemRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb, org.mockito.Mockito.times(2)).deleteItem(deleteCaptor.capture());
        assertThat(deleteCaptor.getAllValues().getFirst().key().get("ticker").s())
                .isEqualTo("RELIANCE.NS");
    }

    @Test
    void deletionPendingWhenFlagTrue() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "deletionPending",
                                AttributeValue.builder().bool(true).build()))
                        .build());

        assertThat(registry.isDeletionPending("user-123")).isTrue();
    }

    @Test
    void notDeletionPendingWhenProfileAbsent() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertThat(registry.isDeletionPending("user-123")).isFalse();
    }

    @Test
    void notDeletionPendingWhenSubMissing() {
        assertThat(registry.isDeletionPending(null)).isFalse();
        assertThat(registry.isDeletionPending("  ")).isFalse();
    }

    @Test
    void deletionPendingFailsClosedOnReadError() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenThrow(new RuntimeException("boom"));

        assertThat(registry.isDeletionPending("user-123")).isTrue();
    }
}
