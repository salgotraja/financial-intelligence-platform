package dev.engnotes.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.model.DailyBar;
import dev.engnotes.ingestion.provider.YahooHistoryProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

class HistoryBackfillServiceTest {

    private DynamoDbClient dynamoDb;
    private YahooHistoryProvider historyProvider;
    private HistoryBackfillService service;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        historyProvider = mock(YahooHistoryProvider.class);
        service = new HistoryBackfillService(dynamoDb, historyProvider);
        ReflectionTestUtils.setField(service, "platformTable", "financial-platform-test");
    }

    private static DailyBar bar(String date, String close, Long volume) {
        return new DailyBar(
                LocalDate.parse(date),
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal(close),
                volume);
    }

    @Test
    void writesOneConditionalDayItemPerBar() {
        when(historyProvider.fetchDailyBars("INFY.NS", "corr-b1"))
                .thenReturn(List.of(bar("2026-07-15", "101.0", 1000L), bar("2026-07-16", "102.5", null)));
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        var result = service.backfill("INFY.NS", "corr-b1");

        assertThat(result.written()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(2)).putItem(captor.capture());
        var first = captor.getAllValues().getFirst();
        assertThat(first.conditionExpression()).isEqualTo("attribute_not_exists(PK) AND attribute_not_exists(SK)");
        assertThat(first.item().get("PK").s()).isEqualTo("TICKER#INFY.NS");
        assertThat(first.item().get("SK").s()).isEqualTo("DAY#2026-07-15");
        assertThat(first.item().get("day").s()).isEqualTo("2026-07-15");
        assertThat(first.item().get("close").n()).isEqualTo("101.0");
        assertThat(first.item().get("backfilled").bool()).isTrue();
        assertThat(first.item())
                .containsKey("volume")
                .doesNotContainKey("series")
                .doesNotContainKey("ttl");
        var second = captor.getAllValues().getLast();
        assertThat(second.item()).doesNotContainKey("volume"); // null volume omitted, never written as 0
    }

    @Test
    void existingRollupWinsAndCountsAsSkipped() {
        when(historyProvider.fetchDailyBars("INFY.NS", "corr-b2"))
                .thenReturn(List.of(bar("2026-07-15", "101.0", 1000L), bar("2026-07-16", "102.5", 900L)));
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder()
                        .message("exists")
                        .build())
                .thenReturn(PutItemResponse.builder().build());

        var result = service.backfill("INFY.NS", "corr-b2");

        assertThat(result.written()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }
}
