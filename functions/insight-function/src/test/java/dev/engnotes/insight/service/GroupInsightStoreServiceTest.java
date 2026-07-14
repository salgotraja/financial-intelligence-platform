package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.exception.InsightException;
import dev.engnotes.insight.model.GroupInsightResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class GroupInsightStoreServiceTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private GroupInsightStoreService store;

    @BeforeEach
    void setUp() {
        store = new GroupInsightStoreService(dynamoDb, TABLE);
    }

    @Test
    void storesLatestOverwriteHistoryAndOneGsi1MirrorPerMember() {
        GroupInsightResponse insight = new GroupInsightResponse(
                "g1",
                List.of("RELIANCE.NS", "TCS.NS"),
                "2026-07-14T10:15:00Z",
                "BULLISH",
                0.72,
                "Correlated group moved together.",
                List.of("driver 1"),
                "RULE_BASED",
                "test-model",
                "v2",
                "corr-1");

        store.store(insight);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(4)).putItem(captor.capture()); // LATEST + HISTORY + 2 member mirrors
        List<PutItemRequest> puts = captor.getAllValues();

        PutItemRequest latest = puts.stream()
                .filter(p -> "GROUP#g1".equals(p.item().get("PK").s())
                        && "INSIGHT#LATEST".equals(p.item().get("SK").s()))
                .findFirst()
                .orElseThrow();
        assertThat(latest.item().get("groupId").s()).isEqualTo("g1");
        assertThat(latest.item().get("tickers").l())
                .extracting(AttributeValue::s)
                .containsExactly("RELIANCE.NS", "TCS.NS");
        assertThat(latest.item()).doesNotContainKey("GSI1PK");
        assertThat(latest.item().get("ttl")).isNotNull();

        PutItemRequest history = puts.stream()
                .filter(p -> "GROUP#g1".equals(p.item().get("PK").s())
                        && "INSIGHT#2026-07-14T10:15:00Z"
                                .equals(p.item().get("SK").s()))
                .findFirst()
                .orElseThrow();
        assertThat(history.item().get("signal").s()).isEqualTo("BULLISH");
        assertThat(history.item().get("ttl")).isNotNull();

        PutItemRequest relianceMirror = puts.stream()
                .filter(p -> "TICKER#RELIANCE.NS"
                        .equals(
                                p.item().containsKey("GSI1PK")
                                        ? p.item().get("GSI1PK").s()
                                        : null))
                .findFirst()
                .orElseThrow();
        assertThat(relianceMirror.item().get("PK").s()).isEqualTo("GROUP#g1");
        assertThat(relianceMirror.item().get("GSI1SK").s()).isEqualTo("INSIGHT#2026-07-14T10:15:00Z");
        assertThat(relianceMirror.item().get("member").s()).isEqualTo("RELIANCE.NS");

        PutItemRequest tcsMirror = puts.stream()
                .filter(p -> "TICKER#TCS.NS"
                        .equals(
                                p.item().containsKey("GSI1PK")
                                        ? p.item().get("GSI1PK").s()
                                        : null))
                .findFirst()
                .orElseThrow();
        assertThat(tcsMirror.item().get("GSI1SK").s()).isEqualTo("INSIGHT#2026-07-14T10:15:00Z");
    }

    @Test
    void latestGeneratedAtReadsTheStoredTimestamp() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "generatedAt",
                                AttributeValue.builder()
                                        .s("2026-07-14T10:15:00Z")
                                        .build()))
                        .build());

        Optional<Instant> latest = store.latestGeneratedAt("g1");

        assertThat(latest).contains(Instant.parse("2026-07-14T10:15:00Z"));
    }

    @Test
    void latestGeneratedAtIsEmptyWhenNoItemExists() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertThat(store.latestGeneratedAt("g1")).isEmpty();
    }

    @Test
    void latestGeneratedAtIsEmptyOnReadFailureInsteadOfThrowing() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenThrow(new RuntimeException("dynamo down"));

        assertThat(store.latestGeneratedAt("g1")).isEmpty();
    }

    @Test
    void wrapsDynamoFailuresInInsightException() {
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenThrow(new RuntimeException("dynamo down"));
        GroupInsightResponse insight = new GroupInsightResponse(
                "g1",
                List.of("RELIANCE.NS"),
                "2026-07-14T10:15:00Z",
                "NEUTRAL",
                0.4,
                "r",
                List.of(),
                "RULE_BASED",
                "m",
                "v2",
                "c");

        assertThatThrownBy(() -> store.store(insight)).isInstanceOf(InsightException.class);
    }
}
