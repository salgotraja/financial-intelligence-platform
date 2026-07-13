package dev.engnotes.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.model.MarketDataResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

class DailyRollupServiceTest {

    private DynamoDbClient dynamoDb;
    private DailyRollupService service;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        service = new DailyRollupService(dynamoDb, "financial-platform-dev");
    }

    private static MarketDataResponse data(String price, Long volume) {
        return dataWithPrevClose(price, volume, null);
    }

    private static MarketDataResponse dataWithPrevClose(String price, Long volume, String previousClose) {
        return MarketDataResponse.builder()
                .ticker("RELIANCE.NS")
                .price(price == null ? null : new BigDecimal(price))
                .volume(volume)
                .previousClose(previousClose == null ? null : new BigDecimal(previousClose))
                .dataSource("yahoo-finance")
                .build();
    }

    private static AttributeValue n(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue point(String t, String p) {
        return AttributeValue.builder().m(Map.of("t", s(t), "p", n(p))).build();
    }

    @Test
    void firstSampleSeedsOpenHighLowCloseWithNoTtl() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        service.upsert(data("100.5", 1000L), Instant.parse("2026-07-13T05:00:00Z"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        Map<String, AttributeValue> item = captor.getValue().item();
        assertThat(item.get("PK").s()).isEqualTo("TICKER#RELIANCE.NS");
        assertThat(item.get("SK").s()).isEqualTo("DAY#2026-07-13");
        assertThat(item.get("open").n()).isEqualTo("100.5");
        assertThat(item.get("high").n()).isEqualTo("100.5");
        assertThat(item.get("low").n()).isEqualTo("100.5");
        assertThat(item.get("close").n()).isEqualTo("100.5");
        assertThat(item.get("samples").n()).isEqualTo("1");
        assertThat(item.get("volume").n()).isEqualTo("1000");
        assertThat(item).doesNotContainKey("ttl");
    }

    @Test
    void laterSampleUpdatesExtremesAndClosePreservingOpen() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "open", n("100"),
                                "high", n("110"),
                                "low", n("95"),
                                "close", n("108"),
                                "samples", n("3")))
                        .build());

        service.upsert(data("90", 2000L), Instant.parse("2026-07-13T06:00:00Z"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        Map<String, AttributeValue> item = captor.getValue().item();
        assertThat(item.get("open").n()).isEqualTo("100");
        assertThat(item.get("high").n()).isEqualTo("110");
        assertThat(item.get("low").n()).isEqualTo("90");
        assertThat(item.get("close").n()).isEqualTo("90");
        assertThat(item.get("samples").n()).isEqualTo("4");
    }

    @Test
    void preservesEarlierVolumeWhenLaterSampleHasNone() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "open", n("100"),
                                "high", n("110"),
                                "low", n("95"),
                                "close", n("108"),
                                "samples", n("3"),
                                "volume", n("1000000")))
                        .build());

        service.upsert(data("104", null), Instant.parse("2026-07-13T06:00:00Z"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertThat(captor.getValue().item().get("volume").n()).isEqualTo("1000000");
    }

    @Test
    void bucketsByIstCalendarDate() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        // 19:30 UTC is 01:00 IST the NEXT day (UTC+05:30).
        service.upsert(data("100", null), Instant.parse("2026-07-13T19:30:00Z"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertThat(captor.getValue().item().get("SK").s()).isEqualTo("DAY#2026-07-14");
    }

    @Test
    void swallowsDynamoFailures() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenThrow(new RuntimeException("dynamo down"));

        assertThatCode(() -> service.upsert(data("100", null), Instant.parse("2026-07-13T05:00:00Z")))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsNullPriceWithoutTouchingDynamo() {
        service.upsert(data(null, 1000L), Instant.parse("2026-07-13T05:00:00Z"));

        verifyNoInteractions(dynamoDb);
    }

    @Test
    void inSessionObservationAppendsSeriesPoint() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        // 05:00 UTC is 10:30 IST on Monday 2026-07-13 (in session).
        service.upsert(data("100.5", 1000L), Instant.parse("2026-07-13T05:00:00Z"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        List<AttributeValue> series = captor.getValue().item().get("series").l();
        assertThat(series).hasSize(1);
        assertThat(series.getFirst().m().get("t").s()).isEqualTo("10:30");
        assertThat(series.getFirst().m().get("p").n()).isEqualTo("100.5");
    }

    @Test
    void outOfSessionObservationPreservesSeriesWithoutAppending() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "open", n("100"),
                                "high", n("110"),
                                "low", n("95"),
                                "close", n("108"),
                                "samples", n("3"),
                                "series",
                                        AttributeValue.builder()
                                                .l(point("10:30", "100.5"))
                                                .build()))
                        .build());

        // 16:00 UTC is 21:30 IST on Monday 2026-07-13 (after close).
        service.upsert(data("104", null), Instant.parse("2026-07-13T16:00:00Z"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        List<AttributeValue> series = captor.getValue().item().get("series").l();
        assertThat(series).hasSize(1);
        assertThat(series.getFirst().m().get("t").s()).isEqualTo("10:30");
        assertThat(series.getFirst().m().get("p").n()).isEqualTo("100.5");
    }

    @Test
    void weekendObservationPreservesSeriesWithoutAppending() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "series",
                                AttributeValue.builder()
                                        .l(point("14:00", "200"))
                                        .build()))
                        .build());

        // 2026-07-12 is a Sunday: 05:00 UTC is 10:30 IST Sunday (weekend).
        service.upsert(data("205", null), Instant.parse("2026-07-12T05:00:00Z"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        List<AttributeValue> series = captor.getValue().item().get("series").l();
        assertThat(series).hasSize(1);
        assertThat(series.getFirst().m().get("t").s()).isEqualTo("14:00");
    }

    @Test
    void storesPreviousClose() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        service.upsert(dataWithPrevClose("100.5", 1000L, "98.25"), Instant.parse("2026-07-13T05:00:00Z"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertThat(captor.getValue().item().get("previousClose").n()).isEqualTo("98.25");
    }

    @Test
    void sameMinuteSecondObservationReplacesRatherThanDuplicates() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "open", n("100.5"),
                                "high", n("100.5"),
                                "low", n("100.5"),
                                "close", n("100.5"),
                                "samples", n("1"),
                                "series",
                                        AttributeValue.builder()
                                                .l(point("10:30", "100.5"))
                                                .build()))
                        .build());

        // 05:00:30 UTC is still 10:30 IST on Monday 2026-07-13.
        service.upsert(data("101", 1000L), Instant.parse("2026-07-13T05:00:30Z"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        List<AttributeValue> series = captor.getValue().item().get("series").l();
        assertThat(series).hasSize(1);
        assertThat(series.getFirst().m().get("t").s()).isEqualTo("10:30");
        assertThat(series.getFirst().m().get("p").n()).isEqualTo("101");
    }
}
