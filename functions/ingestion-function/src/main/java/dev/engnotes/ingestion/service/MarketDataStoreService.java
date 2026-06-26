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
 *
 * Tier 1 - DynamoDB (hot path, TTL 24h)
 *   Fast reads for the query Lambda. Expires automatically via DynamoDB TTL.
 *   Why TTL not delete: TTL is free, eventual, and does not consume write capacity.
 *   Design: PK=ticker, SK=timestamp. Last N records per ticker always available.
 *
 * Tier 2 - S3 data lake (cold path, permanent)
 *   Date-partitioned for Athena queries: yyyy/MM/dd/ticker/HH-mm-ss.json
 *   Enables historical analysis, backtesting, and audit.
 *
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

    @Value("${MARKET_DATA_TABLE:financial-market-data-dev}")
    private String marketDataTable;

    @Value("${DATA_LAKE_BUCKET:financial-platform-datalake-dev}")
    private String dataLakeBucket;

    public MarketDataStoreService(DynamoDbClient dynamoDb, S3Client s3, ObjectMapper objectMapper) {
        this.dynamoDb = dynamoDb;
        this.s3 = s3;
        this.objectMapper = objectMapper;
    }

    /**
     * Stores market data in DynamoDB and S3.
     * Sets stored=true on the response after successful persistence.
     */
    public void store(MarketDataResponse data, String correlationId) {
        String timestamp = Instant.now().toString();

        try {
            storeToDynamoDB(data, timestamp, correlationId);
            storeToS3(data, timestamp, correlationId);
            data.setStored(true);

            log.info(
                    "Market data stored. ticker={} timestamp={} correlationId={}",
                    data.getTicker(),
                    timestamp,
                    correlationId);

        } catch (Exception e) {
            // Log and rethrow - Step Functions will retry via the retry policy
            log.error(
                    "Failed to store market data. ticker={} correlationId={} error={}",
                    data.getTicker(),
                    correlationId,
                    e.getMessage(),
                    e);
            throw new MarketDataException("Storage failed for ticker " + data.getTicker(), e);
        }
    }

    private void storeToDynamoDB(MarketDataResponse data, String timestamp, String correlationId) {
        long ttlEpoch = Instant.now().getEpochSecond() + TTL_SECONDS;

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("ticker", str(data.getTicker()));
        item.put("timestamp", str(timestamp));
        item.put("ttl", num(String.valueOf(ttlEpoch)));
        item.put("correlationId", str(correlationId));
        item.put("dataSource", str(data.getDataSource()));

        // Only store non-null price fields
        if (data.getPrice() != null) item.put("price", num(data.getPrice().toPlainString()));
        if (data.getPreviousClose() != null)
            item.put("previousClose", num(data.getPreviousClose().toPlainString()));
        if (data.getChange() != null) item.put("change", num(data.getChange().toPlainString()));
        if (data.getChangePercent() != null)
            item.put("changePercent", num(data.getChangePercent().toPlainString()));
        if (data.getVolume() != null) item.put("volume", num(String.valueOf(data.getVolume())));
        if (data.getMarketCap() != null)
            item.put("marketCap", num(data.getMarketCap().toPlainString()));
        if (data.getHigh52Week() != null)
            item.put("high52Week", num(data.getHigh52Week().toPlainString()));
        if (data.getLow52Week() != null)
            item.put("low52Week", num(data.getLow52Week().toPlainString()));

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(marketDataTable)
                .item(item)
                // Idempotency: skip if same ticker+timestamp already exists
                .conditionExpression("attribute_not_exists(ticker) AND attribute_not_exists(#ts)")
                .expressionAttributeNames(Map.of("#ts", "timestamp"))
                .build());
    }

    private void storeToS3(MarketDataResponse data, String timestamp, String correlationId) {
        Instant now = Instant.now();

        // Partition by date and ticker for Athena query efficiency.
        // Example key: 2025/06/15/RELIANCE_NS/14-30-00.json
        String s3Key = String.format(
                "%s/%s/%s.json",
                DATE_FORMATTER.format(now), data.getTicker().replace(".", "_"), FILE_FORMATTER.format(now));

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(buildPayload(data, timestamp, correlationId));
        } catch (Exception e) {
            throw new MarketDataException("Failed to serialize market data for ticker " + data.getTicker(), e);
        }

        byte[] payload = jsonPayload.getBytes(StandardCharsets.UTF_8);

        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(dataLakeBucket)
                        .key(s3Key)
                        .contentType("application/json")
                        .contentLength((long) payload.length)
                        // Tag for lifecycle management and cost attribution
                        .tagging("ticker=" + data.getTicker() + "&env=" + System.getenv("ENVIRONMENT"))
                        .build(),
                RequestBody.fromBytes(payload));
    }

    private Map<String, Object> buildPayload(MarketDataResponse data, String timestamp, String correlationId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ticker", data.getTicker());
        payload.put("price", data.getPrice());
        payload.put("previousClose", data.getPreviousClose());
        payload.put("change", data.getChange());
        payload.put("changePercent", data.getChangePercent());
        payload.put("volume", data.getVolume());
        payload.put("marketCap", data.getMarketCap());
        payload.put("high52Week", data.getHigh52Week());
        payload.put("low52Week", data.getLow52Week());
        payload.put("timestamp", timestamp);
        payload.put("correlationId", correlationId);
        payload.put("dataSource", data.getDataSource());
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
