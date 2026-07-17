package dev.engnotes.query.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.query.model.DailyMarketDataRequest;
import dev.engnotes.query.model.DailyMarketDataResponse;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@SpringBootTest
class DailyMarketDataQueryIT extends AbstractLocalStackIT {

    @Autowired
    Function<DailyMarketDataRequest, DailyMarketDataResponse> serveDailyMarketData;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private void seedDay(String ticker, String day, String close) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK", AttributeValue.builder().s("DAY#" + day).build(),
                        "day", AttributeValue.builder().s(day).build(),
                        "open", AttributeValue.builder().n("100.00").build(),
                        "high", AttributeValue.builder().n("105.00").build(),
                        "low", AttributeValue.builder().n("99.00").build(),
                        "close", AttributeValue.builder().n(close).build(),
                        "previousClose", AttributeValue.builder().n("100.00").build(),
                        "volume", AttributeValue.builder().n("50000").build()))
                .build());
    }

    @Test
    void returnsRollupsNewestFirstWithDefaultWindow() {
        seedDay("INFY.NS", "2026-07-14", "101.00");
        seedDay("INFY.NS", "2026-07-15", "102.00");
        seedDay("INFY.NS", "2026-07-16", "103.00");

        DailyMarketDataResponse response =
                serveDailyMarketData.apply(new DailyMarketDataRequest("INFY.NS", null, "corr-dd-1"));

        assertThat(response.found()).isTrue();
        assertThat(response.days()).hasSize(3);
        assertThat(response.days().getFirst().date()).isEqualTo("2026-07-16"); // newest trading day first
        assertThat(response.days().getFirst().close()).isEqualByComparingTo(new BigDecimal("103.00"));
        assertThat(response.days().getLast().date()).isEqualTo("2026-07-14");
        assertThat(response.days().getFirst().volume()).isEqualTo(50000L);
    }

    @Test
    void capsResultsToRequestedDays() {
        seedDay("INFY.NS", "2026-07-14", "101.00");
        seedDay("INFY.NS", "2026-07-15", "102.00");
        seedDay("INFY.NS", "2026-07-16", "103.00");

        DailyMarketDataResponse response =
                serveDailyMarketData.apply(new DailyMarketDataRequest("INFY.NS", "2", "corr-dd-2"));

        assertThat(response.days()).hasSize(2);
        assertThat(response.days().getFirst().date()).isEqualTo("2026-07-16");
        assertThat(response.days().getLast().date()).isEqualTo("2026-07-15");
    }

    @Test
    void returnsNotFoundWhenNoRollups() {
        DailyMarketDataResponse response =
                serveDailyMarketData.apply(new DailyMarketDataRequest("UNKNOWN.NS", "7", "corr-dd-3"));

        assertThat(response.found()).isFalse();
        assertThat(response.days()).isEmpty();
    }
}
