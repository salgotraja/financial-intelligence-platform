package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.model.CorrelationGroup;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

@ExtendWith(MockitoExtension.class)
class GroupResolutionServiceTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private GroupResolutionService service;

    @BeforeEach
    void setUp() {
        service = new GroupResolutionService(dynamoDb, TABLE);
    }

    @Test
    void noReverseLookupIsNoGroup() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertThat(service.resolve("RELIANCE.NS")).isEmpty();
    }

    @Test
    void reverseLookupWithMetaResolvesTheGroup() {
        when(dynamoDb.getItem(reverseLookupRequest())).thenReturn(reverseLookupResponse("g1"));
        when(dynamoDb.getItem(metaRequest("g1"))).thenReturn(metaResponse("g1"));

        Optional<CorrelationGroup> resolved = service.resolve("RELIANCE.NS");

        assertThat(resolved).isPresent();
        CorrelationGroup group = resolved.get();
        assertThat(group.groupId()).isEqualTo("g1");
        assertThat(group.members()).containsExactly("RELIANCE.NS", "TCS.NS");
        assertThat(group.pairwiseRhos()).hasSize(1);
        assertThat(group.pairwiseRhos().getFirst().rho()).isEqualTo(0.82);
        assertThat(group.window()).isEqualTo("30-point window");
        assertThat(group.computedAt()).isEqualTo("2026-07-14T10:15:00Z");
    }

    @Test
    void reverseLookupWithMissingMetaIsOrphanToleratedAsNoGroup() {
        when(dynamoDb.getItem(reverseLookupRequest())).thenReturn(reverseLookupResponse("g1"));
        when(dynamoDb.getItem(metaRequest("g1")))
                .thenReturn(GetItemResponse.builder().build());

        assertThat(service.resolve("RELIANCE.NS")).isEmpty();
    }

    @Test
    void dynamoFailureFallsBackToNoGroupInsteadOfThrowing() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenThrow(new RuntimeException("dynamo down"));

        assertThat(service.resolve("RELIANCE.NS")).isEmpty();
    }

    private static GetItemRequest reverseLookupRequest() {
        return GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("PK", s("TICKER#RELIANCE.NS"), "SK", s("GROUP")))
                .build();
    }

    private static GetItemRequest metaRequest(String groupId) {
        return GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("PK", s("GROUP#" + groupId), "SK", s("META")))
                .build();
    }

    private static GetItemResponse reverseLookupResponse(String groupId) {
        return GetItemResponse.builder()
                .item(Map.of(
                        "PK", s("TICKER#RELIANCE.NS"),
                        "SK", s("GROUP"),
                        "ticker", s("RELIANCE.NS"),
                        "groupId", s(groupId)))
                .build();
    }

    private static GetItemResponse metaResponse(String groupId) {
        return GetItemResponse.builder()
                .item(Map.of(
                        "PK", s("GROUP#" + groupId),
                        "SK", s("META"),
                        "groupId", s(groupId),
                        "members", list(s("RELIANCE.NS"), s("TCS.NS")),
                        "pairwiseRhos", list(rho("RELIANCE.NS", "TCS.NS", 0.82)),
                        "window", s("30-point window"),
                        "computedAt", s("2026-07-14T10:15:00Z")))
                .build();
    }

    private static AttributeValue rho(String a, String b, double value) {
        return AttributeValue.builder()
                .m(Map.of("a", s(a), "b", s(b), "rho", n(value)))
                .build();
    }

    private static AttributeValue list(AttributeValue... values) {
        return AttributeValue.builder().l(List.of(values)).build();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(double value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }
}
