package dev.engnotes.notifier.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Streams-to-WebSocket fan-out: for each INSERTed INSIGHT# item, push the insight (same JSON
 * contract as the read path's QueryResponse) to every connection subscribed to the ticker, and
 * delete rows whose connection is gone (410). Stream events arrive as untyped maps (Jackson-3
 * safety); the ticker comes from the PK key, since insight items do not carry a ticker attribute.
 */
@Service
public class InsightFanout {

    private static final Logger log = LoggerFactory.getLogger(InsightFanout.class);

    private static final String TICKER_PK_PREFIX = "TICKER#";
    private static final String INSIGHT_SK_PREFIX = "INSIGHT#";

    private final DynamoDbClient dynamoDb;
    private final ApiGatewayManagementApiClient management;
    private final ObjectMapper mapper;
    private final String connectionsTable;

    public InsightFanout(
            DynamoDbClient dynamoDb,
            ApiGatewayManagementApiClient management,
            ObjectMapper mapper,
            @Value("${CONNECTIONS_TABLE:financial-connections-dev}") String connectionsTable) {
        this.dynamoDb = dynamoDb;
        this.management = management;
        this.mapper = mapper;
        this.connectionsTable = connectionsTable;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fanOut(Map<String, Object> streamEvent) {
        int delivered = 0;
        int pruned = 0;
        var records = (List<Map<String, Object>>) streamEvent.getOrDefault("Records", List.of());
        for (Map<String, Object> record : records) {
            String pk = null;
            String sk = null;
            try {
                if (!"INSERT".equals(record.get("eventName"))) {
                    continue;
                }
                var dynamodb = (Map<String, Object>) record.getOrDefault("dynamodb", Map.of());
                var keys = (Map<String, Object>) dynamodb.getOrDefault("Keys", Map.of());
                pk = keyString(keys, "PK");
                sk = keyString(keys, "SK");
                if (pk == null || sk == null || !pk.startsWith(TICKER_PK_PREFIX) || !sk.startsWith(INSIGHT_SK_PREFIX)) {
                    continue;
                }
                String ticker = pk.substring(TICKER_PK_PREFIX.length());
                var newImage = (Map<String, Object>) dynamodb.getOrDefault("NewImage", Map.of());
                String payload = mapper.writeValueAsString(insightPayload(ticker, newImage));

                var connections = dynamoDb.query(QueryRequest.builder()
                                .tableName(connectionsTable)
                                .keyConditionExpression("ticker = :t")
                                .expressionAttributeValues(Map.of(
                                        ":t", AttributeValue.builder().s(ticker).build()))
                                .build())
                        .items();
                int recordDelivered = 0;
                int recordPruned = 0;
                for (Map<String, AttributeValue> connection : connections) {
                    String connectionId = connection.get("connectionId").s();
                    try {
                        management.postToConnection(PostToConnectionRequest.builder()
                                .connectionId(connectionId)
                                .data(SdkBytes.fromUtf8String(payload))
                                .build());
                        recordDelivered++;
                    } catch (GoneException e) {
                        try {
                            dynamoDb.deleteItem(DeleteItemRequest.builder()
                                    .tableName(connectionsTable)
                                    .key(Map.of(
                                            "ticker", connection.get("ticker"),
                                            "connectionId", connection.get("connectionId")))
                                    .build());
                            recordPruned++;
                        } catch (RuntimeException deleteFailure) {
                            log.warn(
                                    "Prune delete failed, skipping. connectionId={} error={}",
                                    connectionId,
                                    deleteFailure.getMessage());
                        }
                    } catch (RuntimeException e) {
                        log.warn(
                                "Post to connection failed, skipping. connectionId={} error={}",
                                connectionId,
                                e.getMessage());
                    }
                }
                delivered += recordDelivered;
                pruned += recordPruned;
                log.info(
                        "Insight fan-out. ticker={} connections={} delivered={} pruned={}",
                        ticker,
                        connections.size(),
                        recordDelivered,
                        recordPruned);
            } catch (RuntimeException e) {
                log.warn(
                        "Insight fan-out record processing failed, skipping. pk={} sk={} error={}",
                        pk,
                        sk,
                        e.getMessage());
            }
        }
        return Map.of("delivered", delivered, "pruned", pruned);
    }

    /** Same field names as the read path's QueryResponse so the frontend reuses one parser. */
    private static Map<String, Object> insightPayload(String ticker, Map<String, Object> image) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticker", ticker);
        payload.put("generatedAt", imageString(image, "generatedAt"));
        payload.put("signal", imageString(image, "signal"));
        payload.put("confidence", imageNumber(image, "confidence"));
        payload.put("rationale", imageString(image, "rationale"));
        payload.put("drivers", imageStringList(image, "drivers"));
        payload.put("source", imageString(image, "source"));
        payload.put("insightText", imageString(image, "insightText"));
        payload.put("modelId", imageString(image, "modelId"));
        payload.put("found", true);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static String keyString(Map<String, Object> keys, String name) {
        var attr = (Map<String, Object>) keys.get(name);
        return attr == null ? null : (String) attr.get("S");
    }

    @SuppressWarnings("unchecked")
    private static String imageString(Map<String, Object> image, String name) {
        var attr = (Map<String, Object>) image.get(name);
        return attr == null ? null : (String) attr.get("S");
    }

    @SuppressWarnings("unchecked")
    private static double imageNumber(Map<String, Object> image, String name) {
        var attr = (Map<String, Object>) image.get(name);
        return attr == null || attr.get("N") == null ? 0.0 : Double.parseDouble((String) attr.get("N"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> imageStringList(Map<String, Object> image, String name) {
        var attr = (Map<String, Object>) image.get(name);
        if (attr == null || !(attr.get("L") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(entry -> (String) ((Map<String, Object>) entry).get("S"))
                .toList();
    }
}
