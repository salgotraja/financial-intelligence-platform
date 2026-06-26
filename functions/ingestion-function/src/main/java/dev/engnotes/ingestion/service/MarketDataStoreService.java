package dev.engnotes.ingestion.service;

import dev.engnotes.ingestion.exception.MarketDataException;
import dev.engnotes.ingestion.model.MarketDataResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores market data in two tiers:
 * <p>
 * Tier 1 - DynamoDB (hot path, TTL 24h)
 *   Fast reads for the query Lambda. Expires automatically via DynamoDB TTL.
 *   Why TTL not delete: TTL is free, eventual, and does not consume write capacity.
 *   Design: PK=TICKER#{ticker}, SK=TS#{timestamp}. Last N records per ticker always available.
 * <p>
 * Tier 2 - S3 data lake (cold path, permanent)
 *   Date-partitioned for Athena queries: yyyy/MM/dd/ticker/HH-mm-ss.json
 *   Enables historical analysis, backtesting, and audit.
 * <p>
 * Idempotency:
 *   DynamoDB PutItem with a condition expression prevents double-writes.
 *   S3 key includes timestamp to the second - duplicate events within the same
 *   second overwrite silently (acceptable for market data, last write wins).
 */
@Service
public class MarketDataStoreService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataStoreService.class);

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_FORMATTER =
            DateTimeFormatter.ofPattern("HH-mm-ss").withZone(ZoneOffset.UTC);

    // DynamoDB TTL: 24 hours from now
    private static final long TTL_SECONDS = 24 * 60 * 60;

    private final DynamoDbClient dynamoDb;
    private final S3Client s3;
    private final ObjectMapper objectMapper;

    @Value("${PLATFORM_TABLE:financial-platform-dev}")
    private String platformTable;

    @Value("${DATA_LAKE_BUCKET:financial-platform-datalake-dev}")
    private String dataLakeBucket;

    public MarketDataStoreService(DynamoDbClient dynamoDb, S3Client s3, ObjectMapper objectMapper) {
        this.dynamoDb = dynamoDb;
        this.s3 = s3;
        this.objectMapper = objectMapper;
    }

    /**
     * Stores market data in DynamoDB and S3, returning a copy with stored=true after successful
     * persistence.
     */
    public MarketDataResponse store(MarketDataResponse data, String correlationId) {
        String timestamp = Instant.now().toString();

        try {
            storeToDynamoDB(data, timestamp, correlationId);
            storeToS3(data, timestamp, correlationId);

            log.info(
                    "Market data stored. ticker={} timestamp={} correlationId={}",
                    data.ticker(),
                    timestamp,
                    correlationId);

            return data.withStored(true);

        } catch (Exception e) {
            // Log and rethrow - Step Functions will retry via the retry policy
            log.error(
                    "Failed to store market data. ticker={} correlationId={} error={}",
                    data.ticker(),
                    correlationId,
                    e.getMessage(),
                    e);
            throw new MarketDataException("Storage failed for ticker " + data.ticker(), e);
        }
    }

    private void storeToDynamoDB(MarketDataResponse data, String timestamp, String correlationId) {
        long ttlEpoch = Instant.now().getEpochSecond() + TTL_SECONDS;

        Map<String, AttributeValue> item = new HashMap<>();
        // Single-table keys (spec section 4): PK=TICKER#{ticker}, SK=TS#{iso8601}.
        item.put("PK", str("TICKER#" + data.ticker()));
        item.put("SK", str("TS#" + timestamp));
        item.put("ticker", str(data.ticker()));
        item.put("timestamp", str(timestamp));
        item.put("ttl", num(String.valueOf(ttlEpoch)));
        item.put("correlationId", str(correlationId));
        item.put("dataSource", str(data.dataSource()));

        // Only store non-null price fields
        if (data.price() != null) item.put("price", num(data.price().toPlainString()));
        if (data.previousClose() != null)
            item.put("previousClose", num(data.previousClose().toPlainString()));
        if (data.change() != null) item.put("change", num(data.change().toPlainString()));
        if (data.changePercent() != null)
            item.put("changePercent", num(data.changePercent().toPlainString()));
        if (data.volume() != null) item.put("volume", num(String.valueOf(data.volume())));
        if (data.marketCap() != null) item.put("marketCap", num(data.marketCap().toPlainString()));
        if (data.high52Week() != null)
            item.put("high52Week", num(data.high52Week().toPlainString()));
        if (data.low52Week() != null) item.put("low52Week", num(data.low52Week().toPlainString()));

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(platformTable)
                .item(item)
                // Idempotency: skip if the same PK+SK (ticker + timestamp) already exists
                .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
                .build());
    }

    private void storeToS3(MarketDataResponse data, String timestamp, String correlationId) {
        Instant now = Instant.now();

        // Partition by date and ticker for Athena query efficiency.
        // Example key: 2025/06/15/RELIANCE_NS/14-30-00.json
        String s3Key = String.format(
                "%s/%s/%s.json",
                DATE_FORMATTER.format(now), data.ticker().replace(".", "_"), FILE_FORMATTER.format(now));

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(buildPayload(data, timestamp, correlationId));
        } catch (Exception e) {
            throw new MarketDataException("Failed to serialize market data for ticker " + data.ticker(), e);
        }

        byte[] payload = jsonPayload.getBytes(StandardCharsets.UTF_8);

        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(dataLakeBucket)
                        .key(s3Key)
                        .contentType("application/json")
                        .contentLength((long) payload.length)
                        // Tag for lifecycle management and cost attribution
                        .tagging("ticker=" + data.ticker() + "&env=" + System.getenv("ENVIRONMENT"))
                        .build(),
                RequestBody.fromBytes(payload));
    }

    private Map<String, Object> buildPayload(MarketDataResponse data, String timestamp, String correlationId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ticker", data.ticker());
        payload.put("price", data.price());
        payload.put("previousClose", data.previousClose());
        payload.put("change", data.change());
        payload.put("changePercent", data.changePercent());
        payload.put("volume", data.volume());
        payload.put("marketCap", data.marketCap());
        payload.put("high52Week", data.high52Week());
        payload.put("low52Week", data.low52Week());
        payload.put("timestamp", timestamp);
        payload.put("correlationId", correlationId);
        payload.put("dataSource", data.dataSource());
        return payload;
    }

    // DynamoDB attribute helpers - reduce boilerplate
    private AttributeValue str(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue num(String value) {
        return AttributeValue.builder().n(value).build();
    }
}
