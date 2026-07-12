package dev.engnotes.notifier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class InsightFanoutTest {

    private static final String TABLE = "financial-connections-test";

    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private ApiGatewayManagementApiClient management;

    private InsightFanout fanout;

    @BeforeEach
    void setUp() {
        fanout = new InsightFanout(dynamoDb, management, JsonMapper.builder().build(), TABLE);
    }

    private static Map<String, Object> insertRecord(String pk, String sk, Map<String, Object> newImage) {
        return Map.of(
                "eventName",
                "INSERT",
                "dynamodb",
                Map.of(
                        "Keys",
                        Map.of(
                                "PK", Map.of("S", pk),
                                "SK", Map.of("S", sk)),
                        "NewImage",
                        newImage));
    }

    private static Map<String, Object> insightImage() {
        return Map.of(
                "generatedAt", Map.of("S", "2026-07-12T12:00:00Z"),
                "signal", Map.of("S", "BULLISH"),
                "confidence", Map.of("N", "0.82"),
                "rationale", Map.of("S", "Momentum."),
                "drivers", Map.of("L", List.of(Map.of("S", "volume spike"))),
                "source", Map.of("S", "RULE_BASED"),
                "insightText", Map.of("S", "Momentum."),
                "modelId", Map.of("S", "rules-v1"));
    }

    private void stubConnections(String... connectionIds) {
        var items = java.util.Arrays.stream(connectionIds)
                .map(id -> Map.of(
                        "ticker", AttributeValue.builder().s("RELIANCE.NS").build(),
                        "connectionId", AttributeValue.builder().s(id).build()))
                .toList();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(items).build());
    }

    @Test
    void postsInsightJsonToEverySubscribedConnection() {
        stubConnections("conn-1", "conn-2");
        when(management.postToConnection(any(PostToConnectionRequest.class)))
                .thenReturn(PostToConnectionResponse.builder().build());

        Map<String, Object> event = Map.of(
                "Records", List.of(insertRecord("TICKER#RELIANCE.NS", "INSIGHT#2026-07-12T12:00:00Z", insightImage())));

        Map<String, Object> result = fanout.fanOut(event);

        assertThat(result).containsEntry("delivered", 2).containsEntry("pruned", 0);
        ArgumentCaptor<PostToConnectionRequest> captor = ArgumentCaptor.forClass(PostToConnectionRequest.class);
        verify(management, org.mockito.Mockito.times(2)).postToConnection(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(PostToConnectionRequest::connectionId)
                .containsExactlyInAnyOrder("conn-1", "conn-2");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = JsonMapper.builder()
                .build()
                .readValue(captor.getAllValues().getFirst().data().asUtf8String(), Map.class);
        assertThat(payload)
                .containsEntry("ticker", "RELIANCE.NS")
                .containsEntry("generatedAt", "2026-07-12T12:00:00Z")
                .containsEntry("signal", "BULLISH")
                .containsEntry("confidence", 0.82)
                .containsEntry("rationale", "Momentum.")
                .containsEntry("drivers", List.of("volume spike"))
                .containsEntry("source", "RULE_BASED")
                .containsEntry("insightText", "Momentum.")
                .containsEntry("modelId", "rules-v1")
                .containsEntry("found", true);
    }

    @Test
    void prunesGoneConnections() {
        stubConnections("conn-dead");
        when(management.postToConnection(any(PostToConnectionRequest.class)))
                .thenThrow(GoneException.builder().message("gone").build());

        Map<String, Object> event = Map.of(
                "Records", List.of(insertRecord("TICKER#RELIANCE.NS", "INSIGHT#2026-07-12T12:00:00Z", insightImage())));

        Map<String, Object> result = fanout.fanOut(event);

        assertThat(result).containsEntry("delivered", 0).containsEntry("pruned", 1);
        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb).deleteItem(captor.capture());
        assertThat(captor.getValue().key().get("connectionId").s()).isEqualTo("conn-dead");
    }

    @Test
    void ignoresNonInsightAndNonInsertRecords() {
        Map<String, Object> event = Map.of(
                "Records",
                List.of(
                        insertRecord("TICKER#RELIANCE.NS", "TS#2026-07-12T12:00:00Z", Map.of()),
                        Map.of(
                                "eventName",
                                "MODIFY",
                                "dynamodb",
                                Map.of(
                                        "Keys",
                                        Map.of(
                                                "PK", Map.of("S", "TICKER#RELIANCE.NS"),
                                                "SK", Map.of("S", "INSIGHT#x"))))));

        Map<String, Object> result = fanout.fanOut(event);

        assertThat(result).containsEntry("delivered", 0).containsEntry("pruned", 0);
        verify(dynamoDb, never()).query(any(QueryRequest.class));
        verify(management, never()).postToConnection(any(PostToConnectionRequest.class));
    }
}
