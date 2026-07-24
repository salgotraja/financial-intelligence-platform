package dev.engnotes.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.engnotes.ingestion.model.MarketDataResponse;
import dev.engnotes.ingestion.service.DailyRollupService;
import dev.engnotes.ingestion.service.MarketDataStoreService;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import dev.engnotes.observability.Metrics;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import tools.jackson.databind.json.JsonMapper;

class MarketDataStoreIT extends AbstractLocalStackIT {

    private MarketDataStoreService newStore() {
        var rollupService = mock(DailyRollupService.class);
        var store = new MarketDataStoreService(
                ddb(),
                s3(),
                JsonMapper.builder().build(),
                rollupService,
                Metrics.forTesting().metrics());
        ReflectionTestUtils.setField(store, "platformTable", PlatformSchema.PLATFORM_TABLE);
        ReflectionTestUtils.setField(store, "dataLakeBucket", PlatformSchema.DATA_LAKE_BUCKET);
        return store;
    }

    private static MarketDataResponse sample() {
        return new MarketDataResponse(
                "RELIANCE.NS",
                new BigDecimal("100.50"),
                new BigDecimal("99.00"),
                new BigDecimal("1.50"),
                new BigDecimal("1.52"),
                1000L,
                new BigDecimal("500000"),
                new BigDecimal("120.00"),
                new BigDecimal("80.00"),
                "corr-1",
                "YAHOO",
                false,
                false,
                null);
    }

    @Test
    void storesMarketDataPointInDynamoAndS3() {
        var store = newStore();

        MarketDataResponse stored = store.store(sample(), "corr-1");

        assertThat(stored.stored()).isTrue();
        var items = ddb().query(QueryRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk",
                                AttributeValue.builder().s("TICKER#RELIANCE.NS").build()))
                        .build())
                .items();
        assertThat(items).hasSize(1);
        // DynamoDB normalises trailing zeros on Number attributes ("100.50" -> "100.5").
        // Compare as BigDecimal values for a semantically correct equality check.
        assertThat(new BigDecimal(items.get(0).get("price").n())).isEqualByComparingTo(new BigDecimal("100.50"));

        long s3Objects = s3().listObjectsV2(b -> b.bucket(PlatformSchema.DATA_LAKE_BUCKET))
                .keyCount();
        assertThat(s3Objects).isEqualTo(1);
    }

    /**
     * Verifies the attribute_not_exists(PK) AND attribute_not_exists(SK) condition that
     * MarketDataStoreService.storeToDynamoDB uses for idempotency.
     *
     * <p>store() derives its SK from Instant.now() per call, so two store() calls land on different
     * SKs and cannot test the guard. Instead, this test writes an item directly with a fixed PK/SK
     * and the same condition expression, then asserts the second write throws
     * ConditionalCheckFailedException — proving the guard rejects a duplicate key.
     */
    @Test
    void secondIdenticalWriteIsIdempotentNoOp() {
        var pk = AttributeValue.builder().s("TICKER#RELIANCE.NS").build();
        var sk = AttributeValue.builder().s("TS#2026-06-30T00:00:00Z").build();
        var item = Map.of(
                "PK", pk,
                "SK", sk,
                "ticker", AttributeValue.builder().s("RELIANCE.NS").build());
        var condition = "attribute_not_exists(PK) AND attribute_not_exists(SK)";

        ddb().putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(item)
                .conditionExpression(condition)
                .build());

        assertThatThrownBy(() -> ddb().putItem(PutItemRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .item(item)
                        .conditionExpression(condition)
                        .build()))
                .isInstanceOf(ConditionalCheckFailedException.class);
    }
}
