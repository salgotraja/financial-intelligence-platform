package dev.engnotes.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.model.MarketDataResponse;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectionServiceTest {

    private static final String TABLE = "financial-market-data-test";
    private static final double K = 3.0;
    private static final int MIN_SAMPLES = 5;

    @Mock
    private DynamoDbClient dynamoDb;

    private AnomalyDetectionService service;

    @BeforeEach
    void setUp() {
        service = new AnomalyDetectionService(dynamoDb, TABLE, K, MIN_SAMPLES);
    }

    @Test
    void firstObservationSeedsBaselineAndDoesNotFlag() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build()); // no baseline yet

        MarketDataResponse data =
                base().changePercent(new BigDecimal("2.0")).volume(1000L).build();

        service.evaluate(data, "corr-1");

        assertThat(data.isAnomaly()).isFalse();
        assertThat(data.getAnomalyReason()).isNull();

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        Map<String, AttributeValue> item = captor.getValue().item();
        assertThat(item.get("timestamp").s()).isEqualTo("BASELINE");
        assertThat(item.get("returnCount").n()).isEqualTo("1");
        assertThat(item.get("volumeCount").n()).isEqualTo("1");
        assertThat(item).doesNotContainKey("ttl"); // baseline must persist
    }

    @Test
    void returnSpikeBeyondThresholdFlagsAnomaly() {
        // return baseline: count 10, mean 0, m2 9 -> sample variance 1, std dev 1.
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(baselineItem(10, 0.0, 9.0, 10, 1000.0, 900.0))
                        .build());

        MarketDataResponse data = base().changePercent(new BigDecimal("5.0")) // z = 5 >= 3
                .volume(1000L) // z = 0
                .price(new BigDecimal("100"))
                .high52Week(new BigDecimal("200"))
                .low52Week(new BigDecimal("50"))
                .build();

        service.evaluate(data, "corr-1");

        assertThat(data.isAnomaly()).isTrue();
        assertThat(data.getAnomalyReason()).contains("return z=");
    }

    @Test
    void volumeSpikeBeyondThresholdFlagsAnomaly() {
        // volume baseline: count 10, mean 1000, m2 900 -> sample variance 100, std dev 10.
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(baselineItem(10, 0.0, 9.0, 10, 1000.0, 900.0))
                        .build());

        MarketDataResponse data = base().changePercent(new BigDecimal("0.0")) // z = 0
                .volume(2000L) // z = 100 >= 3
                .price(new BigDecimal("100"))
                .high52Week(new BigDecimal("200"))
                .low52Week(new BigDecimal("50"))
                .build();

        service.evaluate(data, "corr-1");

        assertThat(data.isAnomaly()).isTrue();
        assertThat(data.getAnomalyReason()).contains("volume z=");
    }

    @Test
    void fiftyTwoWeekBreakFlagsAnomalyEvenWithCalmZScores() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(baselineItem(10, 0.0, 9.0, 10, 1000.0, 900.0))
                        .build());

        MarketDataResponse data = base().changePercent(new BigDecimal("0.0"))
                .volume(1000L)
                .price(new BigDecimal("250")) // above the 52-week high
                .high52Week(new BigDecimal("200"))
                .low52Week(new BigDecimal("50"))
                .build();

        service.evaluate(data, "corr-1");

        assertThat(data.isAnomaly()).isTrue();
        assertThat(data.getAnomalyReason()).contains("52-week break");
    }

    @Test
    void calmObservationWithinThresholdDoesNotFlagButUpdatesBaseline() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(baselineItem(10, 0.0, 9.0, 10, 1000.0, 900.0))
                        .build());

        MarketDataResponse data = base().changePercent(new BigDecimal("0.5")) // z = 0.5
                .volume(1005L) // z = 0.5
                .price(new BigDecimal("100"))
                .high52Week(new BigDecimal("200"))
                .low52Week(new BigDecimal("50"))
                .build();

        service.evaluate(data, "corr-1");

        assertThat(data.isAnomaly()).isFalse();
        assertThat(data.getAnomalyReason()).isNull();

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertThat(captor.getValue().item().get("returnCount").n()).isEqualTo("11");
    }

    @Test
    void belowMinSamplesNeverFlagsOnZScore() {
        // Only 3 samples: a huge z must still be ignored during warmup.
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(baselineItem(3, 0.0, 2.0, 3, 1000.0, 200.0))
                        .build());

        MarketDataResponse data = base().changePercent(new BigDecimal("50.0"))
                .volume(99999L)
                .price(new BigDecimal("100"))
                .high52Week(new BigDecimal("200"))
                .low52Week(new BigDecimal("50"))
                .build();

        service.evaluate(data, "corr-1");

        assertThat(data.isAnomaly()).isFalse();
    }

    @Test
    void baselineFailureFailsOpenToNoInsight() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenThrow(new RuntimeException("DynamoDB down"));

        MarketDataResponse data =
                base().changePercent(new BigDecimal("5.0")).volume(1000L).build();

        service.evaluate(data, "corr-1"); // must not throw

        assertThat(data.isAnomaly()).isFalse();
        assertThat(data.getAnomalyReason()).isNull();
    }

    private static MarketDataResponse.Builder base() {
        return MarketDataResponse.builder().ticker("RELIANCE.NS").dataSource("yahoo-finance");
    }

    private static Map<String, AttributeValue> baselineItem(
            long rCount, double rMean, double rM2, long vCount, double vMean, double vM2) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("ticker", str("RELIANCE.NS"));
        item.put("timestamp", str("BASELINE"));
        item.put("returnCount", num(Long.toString(rCount)));
        item.put("returnMean", num(Double.toString(rMean)));
        item.put("returnM2", num(Double.toString(rM2)));
        item.put("volumeCount", num(Long.toString(vCount)));
        item.put("volumeMean", num(Double.toString(vMean)));
        item.put("volumeM2", num(Double.toString(vM2)));
        return item;
    }

    private static AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue num(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
