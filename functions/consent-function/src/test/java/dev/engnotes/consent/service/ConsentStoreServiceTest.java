package dev.engnotes.consent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.consent.model.ConsentRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
class ConsentStoreServiceTest {

    private static final String TABLE = "financial-platform-test";
    private static final String AUDIT = "financial-platform-audit-test";
    private static final String INSTANT = "2026-06-28T00:00:00Z";

    @Mock
    private DynamoDbClient dynamoDb;

    private ConsentStoreService store;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse(INSTANT), ZoneOffset.UTC);
        store = new ConsentStoreService(dynamoDb, TABLE, AUDIT, "v1", clock);
    }

    @Test
    void readReturnsDenyWhenRecordAbsent() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        ConsentRecord record = store.read("user-123");

        assertThat(record.consentGiven()).isFalse();
        assertThat(record.version()).isNull();
    }

    @Test
    void readParsesStoredConsentRecord() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "consentGiven",
                                        AttributeValue.builder().bool(true).build(),
                                "version", AttributeValue.builder().s("v1").build(),
                                "purpose", AttributeValue.builder().s("market").build(),
                                "updatedAt", AttributeValue.builder().s(INSTANT).build()))
                        .build());

        ConsentRecord record = store.read("user-123");

        assertThat(record.consentGiven()).isTrue();
        assertThat(record.version()).isEqualTo("v1");
        assertThat(record.purpose()).isEqualTo("market");
    }

    @Test
    void grantWritesConsentTrueAndGrantedAudit() {
        ConsentRecord record = store.grant("user-123", "v1", "market", "1.2.3.4", "corr-1");

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(2)).putItem(captor.capture());

        PutItemRequest consentPut = captor.getAllValues().get(0);
        assertThat(consentPut.tableName()).isEqualTo(TABLE);
        assertThat(consentPut.item().get("PK").s()).isEqualTo("USER#user-123");
        assertThat(consentPut.item().get("SK").s()).isEqualTo("CONSENT");
        assertThat(consentPut.item().get("consentGiven").bool()).isTrue();
        assertThat(consentPut.item().get("version").s()).isEqualTo("v1");
        assertThat(consentPut.item().get("updatedAt").s()).isEqualTo(INSTANT);

        PutItemRequest auditPut = captor.getAllValues().get(1);
        assertThat(auditPut.tableName()).isEqualTo(AUDIT);
        assertThat(auditPut.item().get("PK").s()).isEqualTo("USER#user-123");
        assertThat(auditPut.item().get("SK").s()).isEqualTo("EVENT#" + INSTANT + "#corr-1");
        assertThat(auditPut.item().get("eventType").s()).isEqualTo("CONSENT_GRANTED");
        assertThat(auditPut.item().get("sourceIp").s()).isEqualTo("1.2.3.4");

        assertThat(record.consentGiven()).isTrue();
    }

    @Test
    void grantDefaultsBlankVersionToConfiguredVersion() {
        store.grant("user-123", "  ", "market", null, "corr-2");

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(2)).putItem(captor.capture());
        assertThat(captor.getAllValues().get(0).item().get("version").s()).isEqualTo("v1");
        PutItemRequest auditPut = captor.getAllValues().get(1);
        assertThat(auditPut.item().get("version").s()).isEqualTo("v1");
    }

    @Test
    void withdrawFlipsConsentFalseAndPreservesVersionAndWritesAudit() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "consentGiven",
                                        AttributeValue.builder().bool(true).build(),
                                "version", AttributeValue.builder().s("v1").build(),
                                "purpose", AttributeValue.builder().s("market").build(),
                                "updatedAt", AttributeValue.builder().s("old").build()))
                        .build());

        ConsentRecord record = store.withdraw("user-123", "9.9.9.9", "corr-3");

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(2)).putItem(captor.capture());

        PutItemRequest consentPut = captor.getAllValues().get(0);
        assertThat(consentPut.tableName()).isEqualTo(TABLE);
        assertThat(consentPut.item().get("consentGiven").bool()).isFalse();
        assertThat(consentPut.item().get("version").s()).isEqualTo("v1");
        assertThat(consentPut.item().get("purpose").s()).isEqualTo("market");
        assertThat(consentPut.item().get("updatedAt").s()).isEqualTo(INSTANT);

        PutItemRequest auditPut = captor.getAllValues().get(1);
        assertThat(auditPut.tableName()).isEqualTo(AUDIT);
        assertThat(auditPut.item().get("SK").s()).isEqualTo("EVENT#" + INSTANT + "#corr-3");
        assertThat(auditPut.item().get("eventType").s()).isEqualTo("CONSENT_WITHDRAWN");

        assertThat(record.consentGiven()).isFalse();
    }

    @Test
    void seedDefaultDenyWritesConsentFalseAndAccountCreatedAudit() {
        store.seedDefaultDeny("user-123");

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(2)).putItem(captor.capture());

        PutItemRequest consentPut = captor.getAllValues().get(0);
        assertThat(consentPut.tableName()).isEqualTo(TABLE);
        assertThat(consentPut.item().get("consentGiven").bool()).isFalse();
        PutItemRequest auditPut = captor.getAllValues().get(1);
        assertThat(auditPut.tableName()).isEqualTo(AUDIT);
        assertThat(auditPut.item().get("eventType").s()).isEqualTo("ACCOUNT_CREATED");
        assertThat(auditPut.item().get("SK").s()).isEqualTo("EVENT#" + INSTANT + "#signup");
    }
}
