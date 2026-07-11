package dev.engnotes.watchlist.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.watchlist.service.WatchlistStoreService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

class WatchlistStoreIT extends AbstractLocalStackIT {

    private WatchlistStoreService newStore() {
        var store = new WatchlistStoreService(ddb(), PlatformSchema.PLATFORM_TABLE);
        ReflectionTestUtils.setField(store, "platformTable", PlatformSchema.PLATFORM_TABLE);
        return store;
    }

    private boolean exists(String pk, String sk) {
        return ddb().getItem(GetItemRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(pk).build(),
                                "SK", AttributeValue.builder().s(sk).build()))
                        .build())
                .hasItem();
    }

    @Test
    void addWritesBothUserItemAndWatchsetMirrorAtomically() {
        newStore().add("user-1", "RELIANCE.NS");

        assertThat(exists("USER#user-1", "WATCH#RELIANCE.NS")).isTrue();
        assertThat(exists("WATCHSET", "TICKER#RELIANCE.NS")).isTrue();
    }

    @Test
    void removeDeletesBothItems() {
        var store = newStore();
        store.add("user-1", "RELIANCE.NS");

        store.remove("user-1", "RELIANCE.NS");

        assertThat(exists("USER#user-1", "WATCH#RELIANCE.NS")).isFalse();
        assertThat(exists("WATCHSET", "TICKER#RELIANCE.NS")).isFalse();
    }

    @Test
    void listReturnsAllUserTickers() {
        var store = newStore();
        store.add("user-1", "AAA.NS");
        store.add("user-1", "BBB.NS");
        store.add("user-2", "CCC.NS");

        assertThat(store.list("user-1")).containsExactlyInAnyOrder("AAA.NS", "BBB.NS");
    }
}
