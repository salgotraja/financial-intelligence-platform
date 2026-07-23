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

/**
 * Covers {@code portfolio} HISTORY against real DynamoDB (LocalStack): a seeded {@code HOLDING#}
 * item with one lot, priced against seeded {@code DAY#} rollups, produces a value-curve floored at
 * the earliest rollup day.
 */
@SpringBootTest
class PortfolioHistoryIT extends AbstractLocalStackIT {

    @Autowired
    Function<PortfolioRequest, PortfolioResponse> portfolio;

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
                                                                        .s("2026-07-20")
                                                                        .build(),
                                                        "qty",
                                                                AttributeValue.builder()
                                                                        .n("10")
                                                                        .build(),
                                                        "price",
                                                                AttributeValue.builder()
                                                                        .n("100")
                                                                        .build()))
                                                .build()))
                                        .build(),
                        "totalQty", AttributeValue.builder().n("10").build(),
                        "avgCost", AttributeValue.builder().n("100").build(),
                        "updatedAt",
                                AttributeValue.builder()
                                        .s("2026-07-22T00:00:00Z")
                                        .build()))
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
    void historyBuildsValueCurveFromSeededRollups() {
        grantConsent("owner-history");
        seedHolding("owner-history", "RELIANCE.NS");
        seedDayRollup("RELIANCE.NS", "2026-07-20", "100");
        seedDayRollup("RELIANCE.NS", "2026-07-21", "110");

        PortfolioResponse response = portfolio.apply(
                new PortfolioRequest(PortfolioOperation.HISTORY, null, null, "owner-history", "corr-it-history-1"));

        assertThat(response.history().points()).isNotEmpty();
        assertThat(response.history().floor()).isEqualTo("2026-07-20");
        var lastPoint =
                response.history().points().get(response.history().points().size() - 1);
        assertThat(lastPoint.value()).isEqualByComparingTo("1100.00");
    }

    @Test
    void historyIncludesNseiBenchmarkOverlay() {
        grantConsent("owner-history-benchmark");
        seedHolding("owner-history-benchmark", "RELIANCE.NS");
        seedDayRollup("RELIANCE.NS", "2026-07-20", "100");
        seedDayRollup("RELIANCE.NS", "2026-07-21", "110");
        seedDayRollup("^NSEI", "2026-07-20", "20000");
        seedDayRollup("^NSEI", "2026-07-21", "21000");

        PortfolioResponse response = portfolio.apply(new PortfolioRequest(
                PortfolioOperation.HISTORY, null, null, "owner-history-benchmark", "corr-it-history-2"));

        assertThat(response.history().benchmark()).isNotEmpty();
        assertThat(response.history().benchmarkFrom())
                .isEqualTo(response.history().floor());
        assertThat(response.history().beatBenchmarkPct()).isNotNull();
    }
}
