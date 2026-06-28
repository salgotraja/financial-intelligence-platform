package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

@ExtendWith(MockitoExtension.class)
class DynamoBatchTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private static WriteRequest delete(String sk) {
        return WriteRequest.builder()
                .deleteRequest(DeleteRequest.builder()
                        .key(Map.of(
                                "PK",
                                AttributeValue.builder().s("USER#u").build(),
                                "SK",
                                AttributeValue.builder().s(sk).build()))
                        .build())
                .build();
    }

    @Test
    void resubmitsUnprocessedItemsThenSucceeds() {
        WriteRequest w = delete("WATCH#A");
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of(TABLE, List.of(w)))
                        .build())
                .thenReturn(BatchWriteItemResponse.builder().build());

        DynamoBatch.batchWriteAllWithRetry(dynamoDb, TABLE, List.of(w));

        verify(dynamoDb, times(2)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void throwsWhenItemsRemainUnprocessedAfterMaxAttempts() {
        WriteRequest w = delete("WATCH#A");
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of(TABLE, List.of(w)))
                        .build());

        assertThatThrownBy(() -> DynamoBatch.batchWriteAllWithRetry(dynamoDb, TABLE, List.of(w)))
                .isInstanceOf(IllegalStateException.class);
        verify(dynamoDb, times(5)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void noOpWhenNoWrites() {
        DynamoBatch.batchWriteAllWithRetry(dynamoDb, TABLE, List.of());
        verify(dynamoDb, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void resubmitsOnlyTheUnprocessedSubset() {
        WriteRequest a = delete("WATCH#A");
        WriteRequest b = delete("WATCH#B");
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of(TABLE, List.of(b)))
                        .build())
                .thenReturn(BatchWriteItemResponse.builder().build());

        DynamoBatch.batchWriteAllWithRetry(dynamoDb, TABLE, List.of(a, b));

        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDb, times(2)).batchWriteItem(captor.capture());
        List<BatchWriteItemRequest> calls = captor.getAllValues();
        assertThat(calls.get(0).requestItems().get(TABLE)).hasSize(2);
        assertThat(calls.get(1).requestItems().get(TABLE)).hasSize(1);
        assertThat(calls.get(1)
                        .requestItems()
                        .get(TABLE)
                        .get(0)
                        .deleteRequest()
                        .key()
                        .get("SK")
                        .s())
                .isEqualTo("WATCH#B");
    }
}
