package dev.engnotes.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.engnotes.ingestion.model.MarketDataResponse;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.json.JsonMapper;

class MarketDataStoreServiceTest {

    private DynamoDbClient dynamoDb;
    private S3Client s3;
    private DailyRollupService rollupService;
    private MarketDataStoreService service;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        s3 = mock(S3Client.class);
        rollupService = mock(DailyRollupService.class);
        service = new MarketDataStoreService(dynamoDb, s3, JsonMapper.builder().build(), rollupService);
    }

    @Test
    void sanitizesCaretIndexTickerInS3Tag() {
        MarketDataResponse data = MarketDataResponse.builder()
                .ticker("^NSEI")
                .price(new BigDecimal("24500.5"))
                .dataSource("yahoo-finance")
                .stored(false)
                .build();

        service.store(data, "corr-1");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        // '^' would be rejected by S3 (400); it must be mapped to a tag-legal character.
        assertThat(captor.getValue().tagging()).contains("ticker=_NSEI");
        assertThat(captor.getValue().tagging()).doesNotContain("^");
    }

    @Test
    void leavesDotTickerUnchangedInS3Tag() {
        MarketDataResponse data = MarketDataResponse.builder()
                .ticker("RELIANCE.NS")
                .price(new BigDecimal("1316.5"))
                .dataSource("yahoo-finance")
                .stored(false)
                .build();

        service.store(data, "corr-2");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().tagging()).contains("ticker=RELIANCE.NS");
    }

    @Test
    void invokesDailyRollupAfterSuccessfulStore() {
        MarketDataResponse data = MarketDataResponse.builder()
                .ticker("RELIANCE.NS")
                .price(new BigDecimal("1316.5"))
                .dataSource("yahoo-finance")
                .stored(false)
                .build();

        service.store(data, "corr-3");

        verify(rollupService).upsert(any(MarketDataResponse.class), any(Instant.class));
    }
}
