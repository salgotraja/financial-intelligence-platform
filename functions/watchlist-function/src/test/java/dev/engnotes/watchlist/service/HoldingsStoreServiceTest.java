package dev.engnotes.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.watchlist.model.Holding;
import dev.engnotes.watchlist.model.Lot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

@ExtendWith(MockitoExtension.class)
class HoldingsStoreServiceTest {

    private static final String TABLE = "financial-platform-test";
    private static final Instant NOW = Instant.parse("2026-07-20T09:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private WatchlistStoreService watchlist;

    private HoldingsStoreService store;

    @BeforeEach
    void setUp() {
        store = new HoldingsStoreService(dynamoDb, watchlist, TABLE, FIXED_CLOCK);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static AttributeValue lotItem(String buyDate, String qty, String price) {
        return AttributeValue.builder()
                .m(Map.of("buyDate", s(buyDate), "qty", n(qty), "price", n(price)))
                .build();
    }

    private void stubAbsentHolding() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());
    }

    private void stubExistingHolding(Map<String, AttributeValue> item) {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());
    }

    @Test
    void upsertOnNewTickerPutsFullItemWithNoLastLotMutationAndAddsToWatchlist() {
        stubAbsentHolding();
        List<Lot> lots = List.of(new Lot(LocalDate.parse("2026-01-10"), 10, new BigDecimal("100.50")));

        store.upsert("user-1", "RELIANCE.NS", lots);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        Map<String, AttributeValue> item = captor.getValue().item();
        assertThat(item.get("PK").s()).isEqualTo("USER#user-1");
        assertThat(item.get("SK").s()).isEqualTo("HOLDING#RELIANCE.NS");
        assertThat(item.get("ticker").s()).isEqualTo("RELIANCE.NS");
        assertThat(item.get("lots").l()).hasSize(1);
        assertThat(item.get("lots").l().get(0).m().get("buyDate").s()).isEqualTo("2026-01-10");
        assertThat(item.get("lots").l().get(0).m().get("qty").n()).isEqualTo("10");
        assertThat(item.get("lots").l().get(0).m().get("price").n()).isEqualTo("100.50");
        assertThat(item.get("totalQty").n()).isEqualTo("10");
        assertThat(item.get("avgCost").n()).isEqualTo("100.5000");
        assertThat(item.get("updatedAt").s()).isEqualTo(NOW.toString());
        assertThat(item).doesNotContainKey("lastLotMutation");

        verify(watchlist).add("user-1", "RELIANCE.NS");
    }

    @Test
    void upsertThatPurelyAddsALotPreservesExistingLastLotMutation() {
        Lot original = new Lot(LocalDate.parse("2026-01-10"), 10, new BigDecimal("100.50"));
        Instant priorMutation = Instant.parse("2026-06-01T00:00:00Z");
        stubExistingHolding(Map.of(
                "PK", s("USER#user-1"),
                "SK", s("HOLDING#RELIANCE.NS"),
                "ticker", s("RELIANCE.NS"),
                "lots",
                        AttributeValue.builder()
                                .l(lotItem("2026-01-10", "10", "100.50"))
                                .build(),
                "totalQty", n("10"),
                "avgCost", n("100.5000"),
                "lastLotMutation", s(priorMutation.toString()),
                "updatedAt", s("2026-06-01T00:00:00Z")));
        Lot added = new Lot(LocalDate.parse("2026-02-01"), 5, new BigDecimal("110.00"));

        store.upsert("user-1", "RELIANCE.NS", List.of(original, added));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        Map<String, AttributeValue> item = captor.getValue().item();
        assertThat(item.get("lots").l()).hasSize(2);
        assertThat(item.get("lastLotMutation").s()).isEqualTo(priorMutation.toString());
        assertThat(item.get("updatedAt").s()).isEqualTo(NOW.toString());
    }

    @Test
    void upsertOnFirstCreationWithSingleLotHasNoLastLotMutation() {
        stubAbsentHolding();

        store.upsert("user-1", "INFY.NS", List.of(new Lot(LocalDate.parse("2026-01-10"), 3, new BigDecimal("1500"))));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertThat(captor.getValue().item()).doesNotContainKey("lastLotMutation");
    }

    @Test
    void upsertThatMutatesAnExistingLotBumpsLastLotMutationToNow() {
        stubExistingHolding(Map.of(
                "PK", s("USER#user-1"),
                "SK", s("HOLDING#RELIANCE.NS"),
                "ticker", s("RELIANCE.NS"),
                "lots",
                        AttributeValue.builder()
                                .l(lotItem("2026-01-10", "10", "100.50"))
                                .build(),
                "totalQty", n("10"),
                "avgCost", n("100.5000"),
                "updatedAt", s("2026-06-01T00:00:00Z")));
        Lot edited = new Lot(LocalDate.parse("2026-01-10"), 8, new BigDecimal("100.50"));

        store.upsert("user-1", "RELIANCE.NS", List.of(edited));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertThat(captor.getValue().item().get("lastLotMutation").s()).isEqualTo(NOW.toString());
    }

    @Test
    void getRoundTripsLotsFaithfully() {
        stubExistingHolding(Map.of(
                "PK", s("USER#user-1"),
                "SK", s("HOLDING#RELIANCE.NS"),
                "ticker", s("RELIANCE.NS"),
                "lots",
                        AttributeValue.builder()
                                .l(lotItem("2026-01-10", "10", "100.50"), lotItem("2026-03-05", "4", "99.999"))
                                .build(),
                "totalQty", n("14"),
                "avgCost", n("100.3565"),
                "lastLotMutation", s("2026-06-01T00:00:00Z"),
                "updatedAt", s("2026-06-15T00:00:00Z")));

        var result = store.get("user-1", "RELIANCE.NS");

        assertThat(result).isPresent();
        StoredHolding stored = result.orElseThrow();
        assertThat(stored.holding())
                .isEqualTo(new Holding(
                        "RELIANCE.NS",
                        List.of(
                                new Lot(LocalDate.parse("2026-01-10"), 10, new BigDecimal("100.50")),
                                new Lot(LocalDate.parse("2026-03-05"), 4, new BigDecimal("99.999")))));
        assertThat(stored.holding().lots().get(0).price().scale()).isEqualTo(2);
        assertThat(stored.holding().lots().get(1).price().scale()).isEqualTo(3);
        assertThat(stored.lastLotMutation()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(stored.updatedAt()).isEqualTo(Instant.parse("2026-06-15T00:00:00Z"));
    }

    @Test
    void getReturnsEmptyWhenAbsent() {
        stubAbsentHolding();

        assertThat(store.get("user-1", "RELIANCE.NS")).isEmpty();
    }

    @Test
    void listReturnsAllHoldingItemsViaPaginator() {
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(
                                Map.of(
                                        "PK", s("USER#user-1"),
                                        "SK", s("HOLDING#RELIANCE.NS"),
                                        "ticker", s("RELIANCE.NS"),
                                        "lots",
                                                AttributeValue.builder()
                                                        .l(lotItem("2026-01-10", "10", "100.50"))
                                                        .build(),
                                        "totalQty", n("10"),
                                        "avgCost", n("100.5000"),
                                        "updatedAt", s("2026-06-01T00:00:00Z")),
                                Map.of(
                                        "PK", s("USER#user-1"),
                                        "SK", s("HOLDING#INFY.NS"),
                                        "ticker", s("INFY.NS"),
                                        "lots",
                                                AttributeValue.builder()
                                                        .l(lotItem("2026-02-01", "5", "1500"))
                                                        .build(),
                                        "totalQty", n("5"),
                                        "avgCost", n("1500.0000"),
                                        "updatedAt", s("2026-06-01T00:00:00Z"))))
                        .build());

        List<StoredHolding> result = store.list("user-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).holding().ticker()).isEqualTo("RELIANCE.NS");
        assertThat(result.get(1).holding().ticker()).isEqualTo("INFY.NS");
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(captor.capture());
        assertThat(captor.getValue().expressionAttributeValues().get(":pk").s()).isEqualTo("USER#user-1");
        assertThat(captor.getValue().expressionAttributeValues().get(":sk").s()).isEqualTo("HOLDING#");
    }

    @Test
    void deleteIssuesDeleteItemOnHoldingKeyAndDoesNotTouchWatchlist() {
        store.delete("user-1", "RELIANCE.NS");

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb).deleteItem(captor.capture());
        assertThat(captor.getValue().key().get("PK").s()).isEqualTo("USER#user-1");
        assertThat(captor.getValue().key().get("SK").s()).isEqualTo("HOLDING#RELIANCE.NS");
        verifyNoInteractions(watchlist);
    }
}
