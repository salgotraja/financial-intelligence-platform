package dev.engnotes.consent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

@ExtendWith(MockitoExtension.class)
class UserWatchlistPurgeServiceTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private UserWatchlistPurgeService purge;

    @BeforeEach
    void setUp() {
        purge = new UserWatchlistPurgeService(dynamoDb, TABLE);
    }

    private void stubPaginator() {
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    @Test
    void purgeDeletesUserItemsAndWatchsetMirrors() {
        stubPaginator();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(
                        QueryResponse.builder()
                                .items(List.of(Map.of("ticker", s("RELIANCE.NS")), Map.of("ticker", s("INFY.NS"))))
                                .build(),
                        QueryResponse.builder().items(List.of()).build());
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());

        purge.purge("user-123");

        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDb).batchWriteItem(captor.capture());
        List<WriteRequest> writes = captor.getValue().requestItems().get(TABLE);
        assertThat(writes)
                .extracting(w -> w.deleteRequest().key().get("PK").s() + "/"
                        + w.deleteRequest().key().get("SK").s())
                .containsExactly(
                        "USER#user-123/WATCH#RELIANCE.NS",
                        "WATCHSET/TICKER#RELIANCE.NS",
                        "USER#user-123/WATCH#INFY.NS",
                        "WATCHSET/TICKER#INFY.NS");
    }

    @Test
    void purgePaginatesAcrossPages() {
        stubPaginator();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("RELIANCE.NS"))))
                        .lastEvaluatedKey(Map.of("PK", s("USER#user-123"), "SK", s("WATCH#RELIANCE.NS")))
                        .build())
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("INFY.NS"))))
                        .build())
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());

        purge.purge("user-123");

        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDb).batchWriteItem(captor.capture());
        assertThat(captor.getValue().requestItems().get(TABLE)).hasSize(4);
    }

    @Test
    void purgeIsNoOpWhenWatchlistAndHoldingsAreBothEmpty() {
        stubPaginator();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());

        purge.purge("user-123");

        verify(dynamoDb, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void purgeDeletesHoldingItemsAndStillPrunesWatchsetMirrors() {
        stubPaginator();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(
                        QueryResponse.builder()
                                .items(List.of(Map.of("ticker", s("RELIANCE.NS"))))
                                .build(),
                        QueryResponse.builder()
                                .items(List.of(Map.of("PK", s("USER#user-123"), "SK", s("HOLDING#RELIANCE.NS"))))
                                .build());
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());

        purge.purge("user-123");

        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDb).batchWriteItem(captor.capture());
        List<WriteRequest> writes = captor.getValue().requestItems().get(TABLE);
        assertThat(writes)
                .extracting(w -> w.deleteRequest().key().get("PK").s() + "/"
                        + w.deleteRequest().key().get("SK").s())
                .containsExactly(
                        "USER#user-123/WATCH#RELIANCE.NS",
                        "WATCHSET/TICKER#RELIANCE.NS",
                        "USER#user-123/HOLDING#RELIANCE.NS");
    }

    @Test
    void purgeDeletesHoldingsWhenWatchlistIsEmpty() {
        stubPaginator();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(
                        QueryResponse.builder().items(List.of()).build(),
                        QueryResponse.builder()
                                .items(List.of(Map.of("PK", s("USER#user-123"), "SK", s("HOLDING#INFY.NS"))))
                                .build());
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().build());

        purge.purge("user-123");

        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDb).batchWriteItem(captor.capture());
        List<WriteRequest> writes = captor.getValue().requestItems().get(TABLE);
        assertThat(writes)
                .extracting(w -> w.deleteRequest().key().get("PK").s() + "/"
                        + w.deleteRequest().key().get("SK").s())
                .containsExactly("USER#user-123/HOLDING#INFY.NS");
    }
}
