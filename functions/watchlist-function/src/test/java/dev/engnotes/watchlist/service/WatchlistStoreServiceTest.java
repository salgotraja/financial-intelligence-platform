package dev.engnotes.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

@ExtendWith(MockitoExtension.class)
class WatchlistStoreServiceTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private WatchlistStoreService store;

    @BeforeEach
    void setUp() {
        store = new WatchlistStoreService(dynamoDb, TABLE);
    }

    @Test
    void addWritesUserItemAndWatchsetUnionEntryUnderGivenSub() {
        store.add("user-123", "RELIANCE.NS");

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(2)).putItem(captor.capture());

        List<PutItemRequest> puts = captor.getAllValues();
        assertThat(puts.get(0).item().get("PK").s()).isEqualTo("USER#user-123");
        assertThat(puts.get(0).item().get("SK").s()).isEqualTo("WATCH#RELIANCE.NS");
        assertThat(puts.get(0).item().get("ticker").s()).isEqualTo("RELIANCE.NS");
        assertThat(puts.get(1).item().get("PK").s()).isEqualTo("WATCHSET");
        assertThat(puts.get(1).item().get("SK").s()).isEqualTo("TICKER#RELIANCE.NS");
    }

    @Test
    void removeDeletesUserItemAndWatchsetUnionEntryUnderGivenSub() {
        store.remove("user-123", "TCS.NS");

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb, times(2)).deleteItem(captor.capture());

        List<DeleteItemRequest> deletes = captor.getAllValues();
        assertThat(deletes.get(0).key().get("PK").s()).isEqualTo("USER#user-123");
        assertThat(deletes.get(0).key().get("SK").s()).isEqualTo("WATCH#TCS.NS");
        assertThat(deletes.get(1).key().get("PK").s()).isEqualTo("WATCHSET");
        assertThat(deletes.get(1).key().get("SK").s()).isEqualTo("TICKER#TCS.NS");
    }

    @Test
    void listQueriesGivenUserPartitionAndReturnsTickers() {
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

        List<String> tickers = store.list("user-123");

        assertThat(tickers).containsExactly("RELIANCE.NS", "INFY.NS");
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(captor.capture());
        assertThat(captor.getValue().expressionAttributeValues().get(":pk").s()).isEqualTo("USER#user-123");
        assertThat(captor.getValue().expressionAttributeValues().get(":sk").s()).isEqualTo("WATCH#");
    }

    @Test
    void listIgnoresItemsMissingTickerAttribute() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(
                                Map.of(
                                        "ticker",
                                        AttributeValue.builder()
                                                .s("RELIANCE.NS")
                                                .build()),
                                Map.of(
                                        "SK",
                                        AttributeValue.builder()
                                                .s("WATCH#BROKEN")
                                                .build())))
                        .build());

        assertThat(store.list("user-123")).containsExactly("RELIANCE.NS");
    }
}
