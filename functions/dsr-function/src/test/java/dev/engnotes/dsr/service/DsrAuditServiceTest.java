package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import dev.engnotes.dsr.model.AuditEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
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
}
