package dev.engnotes.consent.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.consent.service.UserWatchlistPurgeService;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

class WatchlistPurgeIT extends AbstractLocalStackIT {

    private UserWatchlistPurgeService newPurge() {
        var purge = new UserWatchlistPurgeService(ddb(), PlatformSchema.PLATFORM_TABLE);
        ReflectionTestUtils.setField(purge, "platformTable", PlatformSchema.PLATFORM_TABLE);
        return purge;
    }

    private void seedWatch(String sub, String ticker) {
        ddb().putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("WATCH#" + ticker).build(),
                        "ticker", AttributeValue.builder().s(ticker).build()))
                .build());
        ddb().putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("WATCHSET").build(),
                        "SK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "ticker", AttributeValue.builder().s(ticker).build()))
                .build());
    }

    @Test
    void purgeDeletesAllUserWatchItemsAndMirrors() {
        var purge = newPurge();
        seedWatch("user-5", "AAA.NS");
        seedWatch("user-5", "BBB.NS");

        purge.purge("user-5");

        var remaining = ddb().query(QueryRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.builder().s("USER#user-5").build(),
                                ":sk", AttributeValue.builder().s("WATCH#").build()))
                        .build())
                .items();
        assertThat(remaining).isEmpty();

        var mirrors = ddb().query(QueryRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.builder().s("WATCHSET").build(),
                                ":sk", AttributeValue.builder().s("TICKER#").build()))
                        .build())
                .items();
        assertThat(mirrors).isEmpty();
    }
}
