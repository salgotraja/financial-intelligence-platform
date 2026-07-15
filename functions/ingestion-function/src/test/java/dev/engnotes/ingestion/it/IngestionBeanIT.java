package dev.engnotes.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.model.MarketDataRequest;
import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.service.MarketDataFetchService;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@SpringBootTest
class IngestionBeanIT extends AbstractLocalStackIT {

    // 2026-07-15 is a Wednesday and not an NSE 2026 trading holiday (see MarketHours).
    // 05:00 UTC = 10:30 IST: inside the 09:00-15:35 IST session.
    private static final Instant MARKET_OPEN_INSTANT = Instant.parse("2026-07-15T05:00:00Z");
    // Same trading day, 11:00 UTC = 16:30 IST: after the 15:35 IST close.
    private static final Instant MARKET_CLOSED_INSTANT = Instant.parse("2026-07-15T11:00:00Z");

    @MockitoBean
    MarketDataFetchService fetchService;

    // The clock bean gates the scheduled-source market-hours check (IngestionHandler); pinning it
    // here makes the IT deterministic instead of depending on when CI happens to run.
    @MockitoBean
    Clock clock;

    @Autowired
    Function<MarketDataRequest, MarketDataResponse> fetchMarketData;

    @Autowired
    DynamoDbClient dynamoDbClient;

    @Test
    void beanFetchesEvaluatesAndStoresAgainstLocalStack() {
        when(clock.instant()).thenReturn(MARKET_OPEN_INSTANT);
        var fetched = new MarketDataResponse(
                "INFY.NS",
                new BigDecimal("150.00"),
                new BigDecimal("148.00"),
                new BigDecimal("2.00"),
                new BigDecimal("1.35"),
                2000L,
                new BigDecimal("750000"),
                new BigDecimal("180.00"),
                new BigDecimal("110.00"),
                "corr-2",
                "YAHOO",
                false,
                false,
                null);
        when(fetchService.fetch(ArgumentMatchers.eq("INFY.NS"), ArgumentMatchers.anyString()))
                .thenReturn(fetched);

        MarketDataResponse result =
                fetchMarketData.apply(new MarketDataRequest("INFY.NS", "corr-2", "eventbridge-schedule"));

        assertThat(result.stored()).isTrue();
        // AnomalyDetectionService also writes SK=BASELINE for rolling stats.
        // Filter to SK starting with "TS#" to count only the market-data items.
        var items = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :skPrefix)")
                        .expressionAttributeValues(Map.of(
                                ":pk",
                                        AttributeValue.builder()
                                                .s("TICKER#INFY.NS")
                                                .build(),
                                ":skPrefix", AttributeValue.builder().s("TS#").build()))
                        .build())
                .items();
        assertThat(items).hasSize(1);
    }

    @Test
    void beanNoOpsAndStoresNothingWhenMarketClosedForScheduledSource() {
        when(clock.instant()).thenReturn(MARKET_CLOSED_INSTANT);

        MarketDataResponse result =
                fetchMarketData.apply(new MarketDataRequest("TCS.NS", "corr-3", "eventbridge-schedule"));

        assertThat(result.stored()).isFalse();
        assertThat(result.dataSource()).isEqualTo("market-closed");
        var items = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :skPrefix)")
                        .expressionAttributeValues(Map.of(
                                ":pk",
                                        AttributeValue.builder()
                                                .s("TICKER#TCS.NS")
                                                .build(),
                                ":skPrefix", AttributeValue.builder().s("TS#").build()))
                        .build())
                .items();
        assertThat(items).isEmpty();
    }
}
