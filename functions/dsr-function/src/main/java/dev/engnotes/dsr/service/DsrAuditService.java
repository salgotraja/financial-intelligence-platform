package dev.engnotes.dsr.service;

import dev.engnotes.dsr.model.AuditEventType;
import dev.engnotes.dsr.model.ComplianceEventType;
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
 *
 * <p>{@link #recordCompliance} additionally writes the hashed-subject compliance record (spec s11,
 * Task 12): same table, same {@code PutItem}-only IAM, but keyed {@code AUDIT#{type}#{yyyy-MM-dd}} /
 * {@code {occurredAt}#{correlationId}} and carrying only SHA-256 hashes of the subject and actor,
 * never a raw {@code sub} or email. It is the permanent compliance proof; the per-user records above
 * remain the operational trail that powers {@code /user/export} and are not deleted on erasure.
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
     * sent, alongside the same actor/source-ip attribution as {@link #record}. The SK's timestamp
     * component is {@code requestedAt}, not a fresh {@code now} - it is fixed once at workflow start and
     * echoed unchanged through every retry of this state, so a retried {@code WriteErasureAudit} lands
     * on the same {@code PK}/{@code SK} and the {@code PutItem} overwrites in place rather than
     * duplicating (Task 12; append-only IAM grants plain {@code PutItem}, no condition expression, so an
     * overwrite of the same key is allowed).
     */
    public void recordErasureCompletion(
            String subjectSub,
            String actorSub,
            String sourceIp,
            String correlationId,
            String requestedAt,
            String completedAt,
            boolean emailSent) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", s("USER#" + subjectSub));
        item.put("SK", s("EVENT#" + requestedAt + "#" + correlationId));
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

    /**
     * Writes the hashed-subject compliance record (spec s11, Task 12): {@code PK=AUDIT#{type}#{date}}
     * where {@code date} is the {@code yyyy-MM-dd} UTC prefix of {@code occurredAt}, and
     * {@code SK={occurredAt}#{correlationId}}. Both key components are deterministic inputs supplied by
     * the caller (the erasure workflow's stable {@code requestedAt}, or a request-scoped timestamp for
     * export), so retries overwrite the same item instead of duplicating, same as
     * {@link #recordErasureCompletion}. Carries {@code subjectHash} and {@code actorHash} (SHA-256 hex)
     * in place of the raw {@code sub} - admin-on-behalf actions must not leak the admin's raw sub either
     * - and no email or other PII. {@code emailSent} is included only when non-null (erasure records).
     */
    public void recordCompliance(
            ComplianceEventType type,
            String subjectSub,
            String actorSub,
            String occurredAt,
            String correlationId,
            Boolean emailSent) {
        String date = occurredAt.substring(0, 10);
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", s("AUDIT#" + type.name() + "#" + date));
        item.put("SK", s(occurredAt + "#" + correlationId));
        item.put("subjectHash", s(Hashing.sha256Hex(subjectSub)));
        item.put("eventType", s(type.name()));
        item.put("occurredAt", s(occurredAt));
        item.put("actorHash", s(Hashing.sha256Hex(actorSub)));
        if (emailSent != null) {
            item.put("emailSent", AttributeValue.builder().bool(emailSent).build());
        }
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(auditTable).item(item).build());
        log.info("Wrote hashed compliance audit record. type={} date={}", type, date);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
