package dev.engnotes.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

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

    private void stubPaginator() {
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    @Test
    void addWritesBothItemsInOneTransaction() {
        store.add("user-123", "RELIANCE.NS");

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(captor.capture());
        List<TransactWriteItem> items = captor.getValue().transactItems();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).put().item().get("PK").s()).isEqualTo("USER#user-123");
        assertThat(items.get(0).put().item().get("SK").s()).isEqualTo("WATCH#RELIANCE.NS");
        assertThat(items.get(0).put().item().get("ticker").s()).isEqualTo("RELIANCE.NS");
        assertThat(items.get(1).put().item().get("PK").s()).isEqualTo("WATCHSET");
        assertThat(items.get(1).put().item().get("SK").s()).isEqualTo("TICKER#RELIANCE.NS");
        assertThat(items.get(1).put().item().get("ticker").s()).isEqualTo("RELIANCE.NS");
    }

    @Test
    void removeDeletesBothItemsInOneTransaction() {
        store.remove("user-123", "TCS.NS");

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(captor.capture());
        List<TransactWriteItem> items = captor.getValue().transactItems();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).delete().key().get("PK").s()).isEqualTo("USER#user-123");
        assertThat(items.get(0).delete().key().get("SK").s()).isEqualTo("WATCH#TCS.NS");
        assertThat(items.get(1).delete().key().get("PK").s()).isEqualTo("WATCHSET");
        assertThat(items.get(1).delete().key().get("SK").s()).isEqualTo("TICKER#TCS.NS");
    }

    @Test
    void listQueriesGivenUserPartitionAndReturnsTickers() {
        stubPaginator();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("RELIANCE.NS")), Map.of("ticker", s("INFY.NS"))))
                        .build());

        List<String> tickers = store.list("user-123");

        assertThat(tickers).containsExactly("RELIANCE.NS", "INFY.NS");
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(captor.capture());
        assertThat(captor.getValue().expressionAttributeValues().get(":pk").s()).isEqualTo("USER#user-123");
        assertThat(captor.getValue().expressionAttributeValues().get(":sk").s()).isEqualTo("WATCH#");
    }

    @Test
    void listPaginatesAcrossPages() {
        stubPaginator();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("RELIANCE.NS"))))
                        .lastEvaluatedKey(Map.of("PK", s("USER#user-123"), "SK", s("WATCH#RELIANCE.NS")))
                        .build())
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("INFY.NS"))))
                        .build());

        assertThat(store.list("user-123")).containsExactly("RELIANCE.NS", "INFY.NS");
    }

    @Test
    void listIgnoresItemsMissingTickerAttribute() {
        stubPaginator();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("RELIANCE.NS")), Map.of("SK", s("WATCH#BROKEN"))))
                        .build());

        assertThat(store.list("user-123")).containsExactly("RELIANCE.NS");
    }
}
