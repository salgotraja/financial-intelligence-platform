package dev.engnotes.consent.service;

import dev.engnotes.consent.model.AuditEventType;
import dev.engnotes.consent.model.ConsentRecord;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Authoritative consent state (spec section "Data model"). The {@code USER#{sub}/CONSENT} item lives
 * on the single platform table; every lifecycle change also appends an immutable event to the audit
 * table (the app role holds {@code PutItem} only there). Timestamps come from the injected
 * {@link Clock} so they are deterministic in tests.
 */
@Service
public class ConsentStoreService {

    private static final Logger log = LoggerFactory.getLogger(ConsentStoreService.class);

    private final DynamoDbClient dynamoDb;
    private final String platformTable;
    private final String auditTable;
    private final String consentVersion;
    private final Clock clock;

    public ConsentStoreService(
            DynamoDbClient dynamoDb,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable,
            @Value("${AUDIT_TABLE:financial-platform-audit-dev}") String auditTable,
            @Value("${CONSENT_VERSION:v1}") String consentVersion,
            Clock clock) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
        this.auditTable = auditTable;
        this.consentVersion = consentVersion;
        this.clock = clock;
    }

    /** Reads the consent record; an absent record reads as default-deny. */
    public ConsentRecord read(String sub) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", s("USER#" + sub), "SK", s("CONSENT")))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return ConsentRecord.deny();
        }
        Map<String, AttributeValue> item = response.item();
        boolean given = item.containsKey("consentGiven")
                && Boolean.TRUE.equals(item.get("consentGiven").bool());
        return new ConsentRecord(given, str(item, "version"), str(item, "purpose"), str(item, "updatedAt"));
    }

    /** Grants consent (version defaults to CONSENT_VERSION when blank) and audits CONSENT_GRANTED. */
    public ConsentRecord grant(String sub, String version, String purpose, String sourceIp, String seq) {
        String effectiveVersion = (version != null && !version.isBlank()) ? version : consentVersion;
        String now = Instant.now(clock).toString();
        writeConsent(sub, true, effectiveVersion, purpose, now);
        putAudit(sub, AuditEventType.CONSENT_GRANTED, effectiveVersion, purpose, sourceIp, now, seq);
        return new ConsentRecord(true, effectiveVersion, purpose, now);
    }

    /** Flips consent to false (preserving version/purpose) and audits CONSENT_WITHDRAWN. */
    public ConsentRecord withdraw(String sub, String sourceIp, String seq) {
        ConsentRecord current = read(sub);
        String now = Instant.now(clock).toString();
        writeConsent(sub, false, current.version(), current.purpose(), now);
        putAudit(sub, AuditEventType.CONSENT_WITHDRAWN, current.version(), current.purpose(), sourceIp, now, seq);
        return new ConsentRecord(false, current.version(), current.purpose(), now);
    }

    /** Seeds the default-deny baseline at signup and audits ACCOUNT_CREATED. */
    public void seedDefaultDeny(String sub) {
        String now = Instant.now(clock).toString();
        writeConsent(sub, false, null, null, now);
        putAudit(sub, AuditEventType.ACCOUNT_CREATED, null, null, null, now, "signup");
    }

    private void writeConsent(String sub, boolean given, String version, String purpose, String updatedAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", s("USER#" + sub));
        item.put("SK", s("CONSENT"));
        item.put("consentGiven", AttributeValue.builder().bool(given).build());
        item.put("updatedAt", s(updatedAt));
        if (version != null) {
            item.put("version", s(version));
        }
        if (purpose != null) {
            item.put("purpose", s(purpose));
        }
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(platformTable).item(item).build());
        log.info("Wrote consent record. sub={} consentGiven={}", sub, given);
    }

    private void putAudit(
            String sub,
            AuditEventType eventType,
            String version,
            String purpose,
            String sourceIp,
            String at,
            String seq) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", s("USER#" + sub));
        item.put("SK", s("EVENT#" + at + "#" + seq));
        item.put("eventType", s(eventType.name()));
        item.put("actorSub", s(sub));
        if (version != null) {
            item.put("version", s(version));
        }
        if (purpose != null) {
            item.put("purpose", s(purpose));
        }
        if (sourceIp != null) {
            item.put("sourceIp", s(sourceIp));
        }
        dynamoDb.putItem(
                PutItemRequest.builder().tableName(auditTable).item(item).build());
        log.info("Wrote audit event. sub={} eventType={}", sub, eventType);
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
