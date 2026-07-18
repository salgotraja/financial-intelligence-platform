package dev.engnotes.query.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.query.model.DeepAnalysisResponse;
import dev.engnotes.query.model.HorizonStats;
import dev.engnotes.query.model.QueryRequest;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@SpringBootTest
class DeepAnalysisQueryIT extends AbstractLocalStackIT {

    @Autowired
    Function<QueryRequest, DeepAnalysisResponse> serveDeepAnalysis;

    @Autowired
    DynamoDbClient dynamoDbClient;

    // 30 trading days of gently rising closes (100.0, 100.5, ...), weekdays only, with a
    // series attribute on each item to prove the projected read tolerates (ignores) it.
    private void seedThirtyDays(String ticker) {
        LocalDate date = LocalDate.of(2026, 6, 1);
        int seeded = 0;
        double close = 100.0;
        while (seeded < 30) {
            if (date.getDayOfWeek().getValue() <= 5) {
                dynamoDbClient.putItem(PutItemRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .item(Map.of(
                                "PK",
                                        AttributeValue.builder()
                                                .s("TICKER#" + ticker)
                                                .build(),
                                "SK", AttributeValue.builder().s("DAY#" + date).build(),
                                "day",
                                        AttributeValue.builder()
                                                .s(date.toString())
                                                .build(),
                                "open",
                                        AttributeValue.builder()
                                                .n(String.valueOf(close - 0.2))
                                                .build(),
                                "high",
                                        AttributeValue.builder()
                                                .n(String.valueOf(close + 1.0))
                                                .build(),
                                "low",
                                        AttributeValue.builder()
                                                .n(String.valueOf(close - 1.0))
                                                .build(),
                                "close",
                                        AttributeValue.builder()
                                                .n(String.valueOf(close))
                                                .build(),
                                "volume", AttributeValue.builder().n("50000").build(),
                                "series",
                                        AttributeValue.builder()
                                                .l(AttributeValue.builder()
                                                        .m(Map.of(
                                                                "t",
                                                                        AttributeValue.builder()
                                                                                .s("09:15")
                                                                                .build(),
                                                                "p",
                                                                        AttributeValue.builder()
                                                                                .n(String.valueOf(close))
                                                                                .build()))
                                                        .build())
                                                .build()))
                        .build());
                seeded++;
                close += 0.5;
            }
            date = date.plusDays(1);
        }
    }

    private static HorizonStats horizon(DeepAnalysisResponse response, String key) {
        return response.horizons().stream()
                .filter(h -> h.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void computesFullShortHorizonsAndPartialLongOnes() {
        seedThirtyDays("INFY.NS");

        DeepAnalysisResponse response = serveDeepAnalysis.apply(new QueryRequest("INFY.NS", "corr-da-1"));

        assertThat(response.found()).isTrue();
        HorizonStats week = horizon(response, "1W");
        assertThat(week.partial()).isFalse();
        assertThat(week.daysAvailable()).isEqualTo(6);
        assertThat(week.returnPercent().doubleValue()).isGreaterThan(0); // rising closes
        assertThat(week.upDays()).isEqualTo(5);
        assertThat(week.downDays()).isZero();

        HorizonStats month = horizon(response, "1M");
        assertThat(month.partial()).isFalse(); // 30 rows >= 23 needed

        HorizonStats quarter = horizon(response, "3M");
        assertThat(quarter.partial()).isTrue();
        assertThat(quarter.daysAvailable()).isEqualTo(30);

        // No TS# point seeded: the band derives from the window itself.
        assertThat(response.band52w().source()).isEqualTo("DERIVED_1Y");
        assertThat(response.band52w().bandPositionPercent()).isNotNull();
    }

    @Test
    void noHistoryReturnsNotFound() {
        DeepAnalysisResponse response = serveDeepAnalysis.apply(new QueryRequest("UNKNOWN.NS", "corr-da-2"));

        assertThat(response.found()).isFalse();
        assertThat(response.horizons()).isEmpty();
    }
}
