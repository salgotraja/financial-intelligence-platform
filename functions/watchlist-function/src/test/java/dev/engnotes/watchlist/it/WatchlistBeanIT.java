package dev.engnotes.watchlist.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.watchlist.model.Operation;
import dev.engnotes.watchlist.model.WatchlistRequest;
import dev.engnotes.watchlist.model.WatchlistResponse;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@SpringBootTest
class WatchlistBeanIT extends AbstractLocalStackIT {

    @Autowired
    Function<WatchlistRequest, WatchlistResponse> watchlist;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private void grantConsent(String sub) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("CONSENT").build(),
                        "consentGiven", AttributeValue.builder().bool(true).build()))
                .build());
    }

    @Test
    void addThenListRoundTripsThroughLocalStack() {
        grantConsent("user-9");

        watchlist.apply(new WatchlistRequest(Operation.ADD, "WIPRO.NS", "user-9", "corr-1"));
        WatchlistResponse list = watchlist.apply(new WatchlistRequest(Operation.LIST, null, "user-9", "corr-2"));

        assertThat(list.tickers()).containsExactly("WIPRO.NS");
    }
}
