package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

@ExtendWith(MockitoExtension.class)
class UserErasureServiceTest {

    private static final String TABLE = "financial-platform-test";
    private static final String INSTANT = "2026-06-28T00:00:00Z";

    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private CognitoUserService cognito;

    private UserErasureService erasure;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse(INSTANT), ZoneOffset.UTC);
        erasure = new UserErasureService(dynamoDb, cognito, TABLE, clock);
    }

    @Test
    void clearDeletionPendingDeletesProfileItem() {
        erasure.clearDeletionPending("user-1");

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb).deleteItem(captor.capture());
        DeleteItemRequest delete = captor.getValue();
        assertThat(delete.tableName()).isEqualTo(TABLE);
        assertThat(delete.key().get("PK").s()).isEqualTo("USER#user-1");
        assertThat(delete.key().get("SK").s()).isEqualTo("PROFILE");
    }

    @Test
    void clearDeletionPendingIsIdempotent() {
        erasure.clearDeletionPending("user-1");
        erasure.clearDeletionPending("user-1");

        verify(dynamoDb, times(2)).deleteItem(any(DeleteItemRequest.class));
        verify(dynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void deleteUserItemsDeletesConsentAndWatchlistMirrorsWithoutTouchingPendingFlagOrCognito() {
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(
                        QueryResponse.builder()
                                .items(List.of(Map.of("ticker", s("RELIANCE.NS"))))
                                .build(),
                        QueryResponse.builder().items(List.of()).build());
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());

        int itemsDeleted = erasure.deleteUserItems("user-1");

        assertThat(itemsDeleted).isEqualTo(3); // CONSENT + WATCH# + WATCHSET mirror
        verify(dynamoDb, never()).putItem(any(PutItemRequest.class));
        verify(dynamoDb, never()).deleteItem(any(DeleteItemRequest.class));
        verifyNoInteractions(cognito);
    }

    @Test
    void deleteUserItemsPaginatesWatchlistAcrossPages() {
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("RELIANCE.NS"))))
                        .lastEvaluatedKey(Map.of("PK", s("USER#user-1"), "SK", s("WATCH#RELIANCE.NS")))
                        .build())
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("INFY.NS"))))
                        .build())
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());

        int itemsDeleted = erasure.deleteUserItems("user-1");

        // CONSENT + 2 tickers * (WATCH# + WATCHSET) = 5 deletes
        assertThat(itemsDeleted).isEqualTo(5);
    }

    @Test
    void deleteUserItemsDeletesHoldingItems() {
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(
                        QueryResponse.builder()
                                .items(List.of(Map.of("ticker", s("RELIANCE.NS"))))
                                .build(),
                        QueryResponse.builder()
                                .items(List.of(
                                        Map.of("PK", s("USER#user-1"), "SK", s("HOLDING#RELIANCE.NS")),
                                        Map.of("PK", s("USER#user-1"), "SK", s("HOLDING#INFY.NS"))))
                                .build());
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());

        erasure.deleteUserItems("user-1");

        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDb).batchWriteItem(captor.capture());
        List<software.amazon.awssdk.services.dynamodb.model.WriteRequest> writes =
                captor.getValue().requestItems().get(TABLE);
        assertThat(writes)
                .extracting(w -> w.deleteRequest().key().get("PK").s() + "/"
                        + w.deleteRequest().key().get("SK").s())
                .contains("USER#user-1/HOLDING#RELIANCE.NS", "USER#user-1/HOLDING#INFY.NS");
    }

    @Test
    void acquireDeletionLeaseWritesConditionalPutWithLeaseCutoff() {
        boolean acquired = erasure.acquireDeletionLease("sub-1", "2026-06-28T00:00:00Z");

        assertThat(acquired).isTrue();
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        PutItemRequest put = captor.getValue();
        assertThat(put.item().get("PK").s()).isEqualTo("USER#sub-1");
        assertThat(put.item().get("SK").s()).isEqualTo("PROFILE");
        assertThat(put.item().get("deletionPending").bool()).isTrue();
        assertThat(put.item().get("requestedAt").s()).isEqualTo("2026-06-28T00:00:00Z");
        assertThat(put.conditionExpression())
                .isEqualTo("attribute_not_exists(deletionPending) OR deletionPending <> :pending OR requestedAt <"
                        + " :staleCutoff");
        assertThat(put.expressionAttributeValues().get(":pending").bool()).isTrue();
        // cutoff is clock-now minus 5 minutes
        assertThat(put.expressionAttributeValues().get(":staleCutoff").s()).isEqualTo("2026-06-27T23:55:00Z");
    }

    @Test
    void acquireDeletionLeaseReturnsFalseWhenConditionFails() {
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder()
                        .message("in flight")
                        .build());

        assertThat(erasure.acquireDeletionLease("sub-1", "2026-06-28T00:00:00Z"))
                .isFalse();
    }

    @Test
    void s3SafeguardIsANoOpWithNoAwsInteractions() {
        erasure.s3Safeguard("user-1");

        verifyNoInteractions(dynamoDb, cognito);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
