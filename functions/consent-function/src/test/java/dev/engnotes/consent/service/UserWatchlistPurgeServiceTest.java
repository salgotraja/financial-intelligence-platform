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
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

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

    @Test
    void purgeDeletesUserItemsAndWatchsetMirrors() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(
                                Map.of(
                                        "ticker",
                                        AttributeValue.builder()
                                                .s("RELIANCE.NS")
                                                .build()),
                                Map.of(
                                        "ticker",
                                        AttributeValue.builder().s("INFY.NS").build())))
                        .build());

        purge.purge("user-123");

        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDb).batchWriteItem(captor.capture());

        List<WriteRequest> writes = captor.getValue().requestItems().get(TABLE);
        assertThat(writes).hasSize(4);
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
    void purgeIsNoOpOnEmptyWatchlist() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());

        purge.purge("user-123");

        verify(dynamoDb, never()).batchWriteItem(any(BatchWriteItemRequest.class));
    }
}
