package dev.engnotes.query.service;

import dev.engnotes.query.model.QueryResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Read-only serving of the latest stored insight for a ticker (CQRS read path, spec section 10).
 *
 * <p>Design: insights are stored with PK=ticker, SK=generatedAt (ISO-8601). "Latest insight for
 * ticker" is a query on PK=ticker, sorted descending, Limit 1. This path never invokes Bedrock and
 * never writes, matching the read-only IAM grant on the query Lambda.
 *
 * <p>The ticker is validated against a strict allowlist before it reaches the DynamoDB key
 * expression (spec section 12), so a malformed value is rejected at the trust boundary rather than
 * flowing into the query.
 */
@Service
public class InsightQuery {

    private static final Logger log = LoggerFactory.getLogger(InsightQuery.class);

    // Strict ticker allowlist (spec section 12): NSE/BSE symbols and indices, e.g. RELIANCE.NS, ^NSEI.
    private static final Pattern TICKER_PATTERN = Pattern.compile("^[A-Z0-9.^-]{1,15}$");

    private final DynamoDbClient dynamoDb;
    private final String insightTable;

    public InsightQuery(
            DynamoDbClient dynamoDb, @Value("${INSIGHT_TABLE:financial-insights-dev}") String insightTable) {
        this.dynamoDb = dynamoDb;
        this.insightTable = insightTable;
    }

    public QueryResponse findLatestInsight(String ticker) {
        if (ticker == null || !TICKER_PATTERN.matcher(ticker).matches()) {
            throw new IllegalArgumentException("Invalid ticker: " + ticker);
        }

        var request = software.amazon.awssdk.services.dynamodb.model.QueryRequest.builder()
                .tableName(insightTable)
                .keyConditionExpression("ticker = :t")
                .expressionAttributeValues(
                        Map.of(":t", AttributeValue.builder().s(ticker).build()))
                .scanIndexForward(false) // newest generatedAt first
                .limit(1)
                .build();

        List<Map<String, AttributeValue>> items = dynamoDb.query(request).items();

        if (items.isEmpty()) {
            log.info("No insight found. ticker={}", ticker);
            return QueryResponse.notFound(ticker);
        }

        Map<String, AttributeValue> item = items.getFirst();
        return new QueryResponse(
                ticker,
                attr(item, "generatedAt"),
                attr(item, "signal"),
                number(item, "confidence"),
                attr(item, "rationale"),
                stringList(item, "drivers"),
                attr(item, "source"),
                attr(item, "insightText"),
                attr(item, "modelId"),
                true);
    }

    private static String attr(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static double number(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? 0.0 : Double.parseDouble(value.n());
    }

    private static List<String> stringList(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        if (value == null || !value.hasL()) {
            return List.of();
        }
        return value.l().stream().map(AttributeValue::s).toList();
    }
}
