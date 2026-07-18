package dev.engnotes.query.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.query.model.MarketDataResponse;
import dev.engnotes.query.model.QueryRequest;
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
class MarketDataQueryIT extends AbstractLocalStackIT {

    @Autowired
    Function<QueryRequest, MarketDataResponse> serveMarketData;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private void seedPoint(String ticker, String timestamp, String price) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK", AttributeValue.builder().s("TS#" + timestamp).build(),
                        "timestamp", AttributeValue.builder().s(timestamp).build(),
                        "price", AttributeValue.builder().n(price).build(),
                        "changePercent", AttributeValue.builder().n("1.25").build(),
                        "volume", AttributeValue.builder().n("1000").build()))
                .build());
    }

    private void seedDayRollup(String ticker, String day) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK", AttributeValue.builder().s("DAY#" + day).build(),
                        "day", AttributeValue.builder().s(day).build(),
                        "previousClose", AttributeValue.builder().n("148.50").build(),
                        "series",
                                AttributeValue.builder()
                                        .l(
                                                AttributeValue.builder()
                                                        .m(Map.of(
                                                                "t",
                                                                        AttributeValue.builder()
                                                                                .s("09:15")
                                                                                .build(),
                                                                "p",
                                                                        AttributeValue.builder()
                                                                                .n("149.00")
                                                                                .build()))
                                                        .build(),
                                                AttributeValue.builder()
                                                        .m(Map.of(
                                                                "t",
                                                                        AttributeValue.builder()
                                                                                .s("09:16")
                                                                                .build(),
                                                                "p",
                                                                        AttributeValue.builder()
                                                                                .n("150.00")
                                                                                .build()))
                                                        .build())
                                        .build()))
                .build());
    }

    @Test
    void returnsRecentPointsNewestFirstWithDayContext() {
        seedPoint("INFY.NS", "2026-07-15T05:00:00Z", "150.00");
        seedPoint("INFY.NS", "2026-07-15T05:01:00Z", "151.00");
        seedPoint("INFY.NS", "2026-07-15T05:02:00Z", "152.00");
        seedDayRollup("INFY.NS", "2026-07-15");

        MarketDataResponse response = serveMarketData.apply(new QueryRequest("INFY.NS", "corr-md-1"));

        assertThat(response.found()).isTrue();
        assertThat(response.points()).hasSize(3);
        assertThat(response.points().getFirst().price()).isEqualByComparingTo(new BigDecimal("152.00"));
        assertThat(response.points().getLast().price()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(response.daySeries()).hasSize(2);
        assertThat(response.previousClose()).isEqualByComparingTo(new BigDecimal("148.50"));
        assertThat(response.day()).isEqualTo("2026-07-15");
    }

    @Test
    void returnsNotFoundWhenNoData() {
        MarketDataResponse response = serveMarketData.apply(new QueryRequest("UNKNOWN.NS", "corr-md-2"));

        assertThat(response.found()).isFalse();
        assertThat(response.points()).isEmpty();
        assertThat(response.daySeries()).isEmpty();
    }
}
