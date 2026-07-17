package dev.engnotes.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.model.DailyBar;
import dev.engnotes.ingestion.provider.YahooHistoryProvider;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@SpringBootTest
class BackfillDailyHistoryIT extends AbstractLocalStackIT {

    // The HTTP boundary stays mocked (no live Yahoo in ITs); the DynamoDB writes are real.
    @MockitoBean
    YahooHistoryProvider historyProvider;

    @Autowired
    Function<Map<String, Object>, Map<String, Object>> backfillDailyHistory;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private static DailyBar bar(String date, String close) {
        return new DailyBar(
                LocalDate.parse(date),
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal(close),
                1_000_000L);
    }

    private static Map<String, Object> watchsetInsertEvent(String ticker) {
        return Map.of(
                "Records",
                List.of(Map.of(
                        "eventName",
                        "INSERT",
                        "dynamodb",
                        Map.of(
                                "NewImage",
                                Map.of(
                                        "PK", Map.of("S", "WATCHSET"),
                                        "SK", Map.of("S", "TICKER#" + ticker),
                                        "ticker", Map.of("S", ticker))))));
    }

    @Test
    void backfillWritesDayItemsAndNeverOverwritesLiveRollups() {
        // A live rollup for 2026-07-16 exists BEFORE the backfill: it must survive untouched.
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("TICKER#INFY.NS").build(),
                        "SK", AttributeValue.builder().s("DAY#2026-07-16").build(),
                        "day", AttributeValue.builder().s("2026-07-16").build(),
                        "close", AttributeValue.builder().n("999.99").build(),
                        "samples", AttributeValue.builder().n("42").build()))
                .build());
        when(historyProvider.fetchDailyBars(anyString(), anyString()))
                .thenReturn(
                        List.of(bar("2026-07-14", "101.0"), bar("2026-07-15", "102.0"), bar("2026-07-16", "103.0")));

        Map<String, Object> summary = backfillDailyHistory.apply(watchsetInsertEvent("INFY.NS"));

        assertThat(summary)
                .containsEntry("processed", 1)
                .containsEntry("written", 2)
                .containsEntry("skipped", 1);

        var days = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                        .expressionAttributeValues(Map.of(
                                ":pk",
                                        AttributeValue.builder()
                                                .s("TICKER#INFY.NS")
                                                .build(),
                                ":sk", AttributeValue.builder().s("DAY#").build()))
                        .build())
                .items();
        assertThat(days).hasSize(3);

        var liveRollup = dynamoDbClient
                .getItem(GetItemRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .key(Map.of(
                                "PK",
                                        AttributeValue.builder()
                                                .s("TICKER#INFY.NS")
                                                .build(),
                                "SK",
                                        AttributeValue.builder()
                                                .s("DAY#2026-07-16")
                                                .build()))
                        .build())
                .item();
        assertThat(liveRollup.get("close").n()).isEqualTo("999.99"); // untouched
        assertThat(liveRollup).doesNotContainKey("backfilled");

        var backfilledDay = dynamoDbClient
                .getItem(GetItemRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .key(Map.of(
                                "PK",
                                        AttributeValue.builder()
                                                .s("TICKER#INFY.NS")
                                                .build(),
                                "SK",
                                        AttributeValue.builder()
                                                .s("DAY#2026-07-14")
                                                .build()))
                        .build())
                .item();
        assertThat(backfilledDay.get("backfilled").bool()).isTrue();
        assertThat(backfilledDay).doesNotContainKey("ttl").doesNotContainKey("series");
    }

    @Test
    void rerunIsAGapFillNoOp() {
        when(historyProvider.fetchDailyBars(anyString(), anyString()))
                .thenReturn(List.of(bar("2026-07-14", "101.0"), bar("2026-07-15", "102.0")));

        backfillDailyHistory.apply(watchsetInsertEvent("TCS.NS"));
        Map<String, Object> second = backfillDailyHistory.apply(watchsetInsertEvent("TCS.NS"));

        assertThat(second).containsEntry("written", 0).containsEntry("skipped", 2);
    }
}
