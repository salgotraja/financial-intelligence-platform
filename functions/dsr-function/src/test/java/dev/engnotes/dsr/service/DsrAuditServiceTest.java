package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import dev.engnotes.dsr.model.AuditEventType;
import dev.engnotes.dsr.model.ComplianceEventType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@ExtendWith(MockitoExtension.class)
class DsrAuditServiceTest {

    private static final String AUDIT_TABLE = "financial-platform-audit-test";
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private DynamoDbClient dynamoDb;

    @Test
    void recordWritesAppendOnlyAuditItem() {
        DsrAuditService audit = new DsrAuditService(dynamoDb, AUDIT_TABLE, clock);

        audit.record("user-2", AuditEventType.DATA_EXPORTED, "admin-1", "1.2.3.4", "corr-9");

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        PutItemRequest req = captor.getValue();

        assertThat(req.tableName()).isEqualTo(AUDIT_TABLE);
        assertThat(req.item().get("PK").s()).isEqualTo("USER#user-2");
        assertThat(req.item().get("SK").s()).isEqualTo("EVENT#2026-06-28T10:00:00Z#corr-9");
        assertThat(req.item().get("eventType").s()).isEqualTo("DATA_EXPORTED");
        assertThat(req.item().get("actorSub").s()).isEqualTo("admin-1");
        assertThat(req.item().get("sourceIp").s()).isEqualTo("1.2.3.4");
    }

    @Test
    void recordOmitsSourceIpWhenNull() {
        DsrAuditService audit = new DsrAuditService(dynamoDb, AUDIT_TABLE, clock);

        audit.record("user-2", AuditEventType.ACCOUNT_ERASED, "user-2", null, "corr-1");

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertThat(captor.getValue().item()).doesNotContainKey("sourceIp");
    }

    @Test
    void recordErasureCompletionWritesTimestampsAndEmailSentFlag() {
        DsrAuditService audit = new DsrAuditService(dynamoDb, AUDIT_TABLE, clock);

        audit.recordErasureCompletion(
                "user-3", "admin-1", "1.2.3.4", "corr-7", "2026-06-28T09:00:00Z", "2026-06-28T09:05:00Z", true);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        var item = captor.getValue().item();
        assertThat(item.get("PK").s()).isEqualTo("USER#user-3");
        assertThat(item.get("SK").s()).isEqualTo("EVENT#2026-06-28T09:00:00Z#corr-7");
        assertThat(item.get("eventType").s()).isEqualTo("ACCOUNT_ERASED");
        assertThat(item.get("actorSub").s()).isEqualTo("admin-1");
        assertThat(item.get("sourceIp").s()).isEqualTo("1.2.3.4");
        assertThat(item.get("requestedAt").s()).isEqualTo("2026-06-28T09:00:00Z");
        assertThat(item.get("completedAt").s()).isEqualTo("2026-06-28T09:05:00Z");
        assertThat(item.get("emailSent").bool()).isTrue();
    }

    @Test
    void recordErasureCompletionWritesEmailSentFalse() {
        DsrAuditService audit = new DsrAuditService(dynamoDb, AUDIT_TABLE, clock);

        audit.recordErasureCompletion(
                "user-4", "user-4", null, "corr-8", "2026-06-28T09:00:00Z", "2026-06-28T09:05:00Z", false);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        var item = captor.getValue().item();
        assertThat(item.get("emailSent").bool()).isFalse();
        assertThat(item).doesNotContainKey("sourceIp");
    }

    @Test
    void recordErasureCompletionRetryWithSameRequestedAtAndCorrelationIdOverwritesSameKey() {
        DsrAuditService audit = new DsrAuditService(dynamoDb, AUDIT_TABLE, clock);

        // First attempt fails downstream after the write; the state machine retries the whole state,
        // which re-invokes this method with the same requestedAt/correlationId but a later completedAt.
        audit.recordErasureCompletion(
                "user-5", "user-5", null, "corr-retry", "2026-06-28T09:00:00Z", "2026-06-28T09:05:00Z", false);
        audit.recordErasureCompletion(
                "user-5", "user-5", null, "corr-retry", "2026-06-28T09:00:00Z", "2026-06-28T09:06:00Z", true);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, org.mockito.Mockito.times(2)).putItem(captor.capture());
        var keys = captor.getAllValues().stream()
                .map(req ->
                        req.item().get("PK").s() + "|" + req.item().get("SK").s())
                .distinct()
                .toList();
        assertThat(keys).hasSize(1).containsExactly("USER#user-5|EVENT#2026-06-28T09:00:00Z#corr-retry");
    }

    @Test
    void recordComplianceWritesHashedErasureRecordWithNoRawPii() {
        DsrAuditService audit = new DsrAuditService(dynamoDb, AUDIT_TABLE, clock);

        audit.recordCompliance(
                ComplianceEventType.ERASURE, "sub-abc-123", "admin-sub-999", "2026-06-28T09:05:00Z", "corr-7", true);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        PutItemRequest req = captor.getValue();
        var item = req.item();

        assertThat(req.tableName()).isEqualTo(AUDIT_TABLE);
        assertThat(item.get("PK").s()).isEqualTo("AUDIT#ERASURE#2026-06-28");
        assertThat(item.get("SK").s()).isEqualTo("2026-06-28T09:05:00Z#corr-7");
        // Independent SHA-256 vector, computed once via `printf 'sub-abc-123' | shasum -a 256` - not
        // derived from the production implementation, so a broken hash cannot self-verify.
        assertThat(item.get("subjectHash").s())
                .isEqualTo("e594381c308ef23318d045d4b5188940da1591293dbb6b68b2a247bafdc151ed");
        assertThat(item.get("actorHash").s()).isEqualTo(sha256Hex("admin-sub-999"));
        assertThat(item.get("eventType").s()).isEqualTo("ERASURE");
        assertThat(item.get("occurredAt").s()).isEqualTo("2026-06-28T09:05:00Z");
        assertThat(item.get("emailSent").bool()).isTrue();

        // No raw PII anywhere in the item: walk every attribute value and assert none is the raw sub,
        // the raw actor sub, or an email address.
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            String value = entry.getValue().s();
            if (value == null) {
                continue;
            }
            assertThat(value)
                    .doesNotContain("sub-abc-123")
                    .doesNotContain("admin-sub-999")
                    .doesNotContain("@");
        }
        assertThat(item).doesNotContainKey("email").doesNotContainKey("sub").doesNotContainKey("sourceIp");
    }

    @Test
    void recordComplianceWritesHashedAccessRecordWithoutEmailSentAttribute() {
        DsrAuditService audit = new DsrAuditService(dynamoDb, AUDIT_TABLE, clock);

        audit.recordCompliance(
                ComplianceEventType.ACCESS, "sub-xyz-1", "sub-xyz-1", "2026-06-28T10:15:30Z", "corr-11", null);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        var item = captor.getValue().item();

        assertThat(item.get("PK").s()).isEqualTo("AUDIT#ACCESS#2026-06-28");
        assertThat(item.get("SK").s()).isEqualTo("2026-06-28T10:15:30Z#corr-11");
        assertThat(item.get("eventType").s()).isEqualTo("ACCESS");
        assertThat(item).doesNotContainKey("emailSent");
    }

    @Test
    void recordComplianceHashIsStableForTheSameSubject() {
        DsrAuditService audit = new DsrAuditService(dynamoDb, AUDIT_TABLE, clock);

        audit.recordCompliance(
                ComplianceEventType.ACCESS, "same-subject", "same-subject", "2026-06-28T10:00:00Z", "corr-a", null);
        audit.recordCompliance(
                ComplianceEventType.ACCESS, "same-subject", "same-subject", "2026-06-28T11:00:00Z", "corr-b", null);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, org.mockito.Mockito.times(2)).putItem(captor.capture());
        var hashes = captor.getAllValues().stream()
                .map(req -> req.item().get("subjectHash").s())
                .distinct()
                .toList();
        assertThat(hashes).hasSize(1).containsExactly(sha256Hex("same-subject"));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
