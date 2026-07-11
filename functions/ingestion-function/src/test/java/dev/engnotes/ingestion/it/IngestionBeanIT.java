package dev.engnotes.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.engnotes.ingestion.model.MarketDataRequest;
import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.service.MarketDataFetchService;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.math.BigDecimal;
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

    @MockitoBean
    MarketDataFetchService fetchService;

    @Autowired
    Function<MarketDataRequest, MarketDataResponse> fetchMarketData;

    @Autowired
    DynamoDbClient dynamoDbClient;

    @Test
    void beanFetchesEvaluatesAndStoresAgainstLocalStack() {
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

        MarketDataResponse result = fetchMarketData.apply(new MarketDataRequest("INFY.NS", "corr-2"));

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
}
