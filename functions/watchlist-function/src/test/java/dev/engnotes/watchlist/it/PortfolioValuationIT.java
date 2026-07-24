package dev.engnotes.watchlist.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.watchlist.model.PortfolioOperation;
import dev.engnotes.watchlist.model.PortfolioRequest;
import dev.engnotes.watchlist.model.PortfolioResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Covers {@code portfolio} LIST against real DynamoDB (LocalStack): a seeded {@code HOLDING#} item
 * priced against a seeded TS# intraday point (mirrors {@code DsrBeanIT.seedHolding}'s item shape),
 * and the weekend fallback where only a DAY# rollup exists.
 */
@SpringBootTest
class PortfolioValuationIT extends AbstractLocalStackIT {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    // The portfolio bean takes the raw JSON body (String) and deserializes it itself so a malformed
    // body maps to a 400, not a 500; ITs serialize the request the same way API Gateway does.
    @Autowired
    Function<String, PortfolioResponse> portfolio;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private static String json(PortfolioRequest request) {
        return MAPPER.writeValueAsString(request);
    }

    private void grantConsent(String sub) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("CONSENT").build(),
                        "consentGiven", AttributeValue.builder().bool(true).build()))
                .build());
    }

    private void seedHolding(String sub, String ticker) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("HOLDING#" + ticker).build(),
                        "ticker", AttributeValue.builder().s(ticker).build(),
                        "lots",
                                AttributeValue.builder()
                                        .l(List.of(AttributeValue.builder()
                                                .m(Map.of(
                                                        "buyDate",
                                                                AttributeValue.builder()
                                                                        .s("2020-01-15")
                                                                        .build(),
                                                        "qty",
                                                                AttributeValue.builder()
                                                                        .n("10")
                                                                        .build(),
                                                        "price",
                                                                AttributeValue.builder()
                                                                        .n("100.5")
                                                                        .build()))
                                                .build()))
                                        .build(),
                        "totalQty", AttributeValue.builder().n("10").build(),
                        "avgCost", AttributeValue.builder().n("100.5").build(),
                        "updatedAt",
                                AttributeValue.builder()
                                        .s("2026-07-22T00:00:00Z")
                                        .build()))
                .build());
    }

    private void seedTsPoint(String ticker, String timestamp, String price) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK", AttributeValue.builder().s("TS#" + timestamp).build(),
                        "price", AttributeValue.builder().n(price).build(),
                        "timestamp", AttributeValue.builder().s(timestamp).build()))
                .build());
    }

    private void seedDayRollup(String ticker, String day, String close) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#" + ticker).build(),
                        "SK", AttributeValue.builder().s("DAY#" + day).build(),
                        "close", AttributeValue.builder().n(close).build(),
                        "day", AttributeValue.builder().s(day).build()))
                .build());
    }

    @Test
    void listPricesHoldingFromLatestTsPoint() {
        grantConsent("owner-ts");
        seedHolding("owner-ts", "RELIANCE.NS");
        seedTsPoint("RELIANCE.NS", "2026-07-23T10:00:00Z", "120");

        PortfolioResponse response = portfolio.apply(
                json(new PortfolioRequest(PortfolioOperation.LIST, null, null, "owner-ts", "corr-it-1")));

        assertThat(response.portfolio().holdings()).hasSize(1);
        var view = response.portfolio().holdings().get(0);
        assertThat(view.degraded()).isFalse();
        assertThat(view.ltp()).isEqualByComparingTo("120");
    }

    @Test
    void listFallsBackToDayCloseWhenNoTsPoint() {
        grantConsent("owner-day");
        seedHolding("owner-day", "TCS.NS");
        seedDayRollup("TCS.NS", "2026-07-22", "118.5");

        PortfolioResponse response = portfolio.apply(
                json(new PortfolioRequest(PortfolioOperation.LIST, null, null, "owner-day", "corr-it-2")));

        assertThat(response.portfolio().holdings()).hasSize(1);
        var view = response.portfolio().holdings().get(0);
        assertThat(view.degraded()).isFalse();
        assertThat(view.ltp()).isEqualByComparingTo("118.5");
    }
}
