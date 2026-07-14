package dev.engnotes.dsr.service;

import dev.engnotes.dsr.model.AuditEventType;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Appends DSR events (DATA_EXPORTED, ACCOUNT_ERASED) to the shared append-only audit table. The role
 * holds {@code PutItem} only there. {@code actorSub} (the caller) is recorded distinctly from the
 * subject {@code PK}, so admin-on-behalf actions are attributable. Timestamps come from the injected
 * {@link Clock} for deterministic tests.
 */
@Service
public class DsrAuditService {

    private static final Logger log = LoggerFactory.getLogger(DsrAuditService.class);

    private final DynamoDbClient dynamoDb;
    private final String auditTable;
    private final Clock clock;

    public DsrAuditService(
            DynamoDbClient dynamoDb,
            @Value("${AUDIT_TABLE:financial-platform-audit-dev}") String auditTable,
            Clock clock) {
        this.dynamoDb = dynamoDb;
        this.auditTable = auditTable;
        this.clock = clock;
    }

    public void record(String subjectSub, AuditEventType type, String actorSub, String sourceIp, String seq) {
        String now = Instant.now(clock).toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", s("USER#" + subjectSub));
        item.put("SK", s("EVENT#" + now + "#" + seq));
        item.put("eventType", s(type.name()));
        item.put("actorSub", s(actorSub));
        if (sourceIp != null) {
            item.put("sourceIp", s(sourceIp));
        }
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(auditTable).item(item).build());
        log.info("Wrote DSR audit event. subjectSub={} eventType={} actorSub={}", subjectSub, type, actorSub);
    }

    /**
     * Writes the {@code WriteErasureAudit} state's {@code ACCOUNT_ERASED} record (spec s11, Task 11):
     * the erasure workflow's request and completion timestamps plus whether the confirmation email
     * sent, alongside the same actor/source-ip attribution as {@link #record}. A clean seam for Task 12
     * to add the hashed-subject compliance record: same event, one more attribute.
     */
    public void recordErasureCompletion(
            String subjectSub,
            String actorSub,
            String sourceIp,
            String correlationId,
            String requestedAt,
            String completedAt,
            boolean emailSent) {
        String now = Instant.now(clock).toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", s("USER#" + subjectSub));
        item.put("SK", s("EVENT#" + now + "#" + correlationId));
        item.put("eventType", s(AuditEventType.ACCOUNT_ERASED.name()));
        item.put("actorSub", s(actorSub));
        if (sourceIp != null) {
            item.put("sourceIp", s(sourceIp));
        }
        item.put("requestedAt", s(requestedAt));
        item.put("completedAt", s(completedAt));
        item.put("emailSent", AttributeValue.builder().bool(emailSent).build());
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(auditTable).item(item).build());
        log.info(
                "Wrote DSR erasure audit event. subjectSub={} actorSub={} emailSent={}",
                subjectSub,
                actorSub,
                emailSent);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
