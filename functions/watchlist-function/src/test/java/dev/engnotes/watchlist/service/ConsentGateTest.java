package dev.engnotes.watchlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;
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
class ConsentGateTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private ConsentGate gate;

    @BeforeEach
    void setUp() {
        gate = new ConsentGate(dynamoDb, TABLE);
    }

    @Test
    void activeWhenConsentGivenTrue() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "consentGiven",
                                AttributeValue.builder().bool(true).build()))
                        .build());

        assertThat(gate.isActive("user-123")).isTrue();
    }

    @Test
    void inactiveWhenConsentGivenFalse() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "consentGiven",
                                AttributeValue.builder().bool(false).build()))
                        .build());

        assertThat(gate.isActive("user-123")).isFalse();
    }

    @Test
    void inactiveWhenRecordAbsent() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertThat(gate.isActive("user-123")).isFalse();
    }

    @Test
    void failsClosedOnReadError() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenThrow(new RuntimeException("boom"));

        assertThat(gate.isActive("user-123")).isFalse();
    }
}
