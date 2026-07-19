package dev.engnotes.dsr.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.engnotes.dsr.model.DsrOperation;
import dev.engnotes.dsr.model.DsrRequest;
import dev.engnotes.dsr.model.DsrResponse;
import dev.engnotes.dsr.model.ErasureResult;
import dev.engnotes.dsr.model.UserDataExport;
import dev.engnotes.dsr.service.CognitoUserService;
import dev.engnotes.dsr.service.ErasureEmailService;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Covers the DSR bean's EXPORT route and the collapsed ERASE cascade against real DynamoDB
 * (LocalStack). The stepwise erasure-workflow tests that used to live here were deleted 2026-07-19
 * (Task 3): they invoked the state machine's per-state operations, now removed, directly. Task 4
 * rewrites ERASE coverage against the whole-cascade shape ({@link dev.engnotes.dsr.service.ErasureService}):
 * one call in, one {@link ErasureResult} out, asserted on its externally visible effects (items gone,
 * both audit records written) rather than on intermediate per-state records.
 *
 * <p>Cognito and SES are not in LocalStack Community, so {@link CognitoUserService} and
 * {@link ErasureEmailService} are mocked, same mechanism the pre-collapse stepwise ITs used. Neither
 * mock is stubbed here: the cascade's own null/false handling (no captured email, no Cognito user) is
 * exactly what these tests exercise, since the seeded subjects have no matching Cognito identity.
 *
 * <p>Lease seeds below use real wall-clock {@code Instant.now()}: the Spring context wires the
 * production {@code Clock.systemUTC()} bean ({@link dev.engnotes.dsr.config.AwsClientConfig#clock()}),
 * and this IT does not override it, so the 5-minute staleness cutoff in
 * {@link dev.engnotes.dsr.service.UserErasureService#acquireDeletionLease} is computed against real time.
 */
@SpringBootTest
class DsrBeanIT extends AbstractLocalStackIT {

    @MockitoBean
    CognitoUserService cognitoUserService;

    @MockitoBean
    ErasureEmailService confirmationEmail;

    @Autowired
    Function<DsrRequest, DsrResponse> dsr;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private void seedConsent(String sub) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("CONSENT").build(),
                        "consentGiven", AttributeValue.builder().bool(true).build(),
                        "version", AttributeValue.builder().s("v1").build(),
                        "purpose", AttributeValue.builder().s("analytics").build(),
                        "updatedAt",
                                AttributeValue.builder()
                                        .s("2026-06-30T10:00:00Z")
                                        .build()))
                .build());
    }

    private void seedWatch(String sub, String ticker) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("WATCH#" + ticker).build(),
                        "ticker", AttributeValue.builder().s(ticker).build()))
                .build());
    }

    /** Seeds a deletion-pending lease directly on the platform table's {@code PROFILE} row. */
    private void putProfileLease(String sub, String requestedAt) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("PROFILE").build(),
                        "deletionPending", AttributeValue.builder().bool(true).build(),
                        "requestedAt", AttributeValue.builder().s(requestedAt).build()))
                .build());
    }

    /** Reads a platform-table item by key, or {@code null} if absent. */
    private Map<String, AttributeValue> getItem(String pk, String sk) {
        var response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(pk).build(),
                        "SK", AttributeValue.builder().s(sk).build()))
                .build());
        return response.hasItem() ? response.item() : null;
    }

    /** Asserts the per-user {@code ACCOUNT_ERASED} audit row exists, keyed by requestedAt#correlationId. */
    private void assertAuditEventWritten(String pk, String eventType, String correlationId) {
        var items = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.AUDIT_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(
                                Map.of(":pk", AttributeValue.builder().s(pk).build()))
                        .build())
                .items();
        boolean found = items.stream()
                .anyMatch(item -> item.get("SK").s().endsWith("#" + correlationId)
                        && eventType.equals(item.get("eventType").s()));
        assertThat(found)
                .as("expected an %s audit row for %s under PK=%s", eventType, correlationId, pk)
                .isTrue();
    }

    /** Asserts the hashed compliance row exists under today's {@code AUDIT#{type}#{date}} partition. */
    private void assertComplianceRowWritten(String type, String correlationId) {
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        var items = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.AUDIT_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk",
                                AttributeValue.builder()
                                        .s("AUDIT#" + type + "#" + date)
                                        .build()))
                        .build())
                .items();
        boolean found = items.stream().anyMatch(item -> item.get("SK").s().endsWith("#" + correlationId));
        assertThat(found)
                .as("expected a %s compliance row for %s under date=%s", type, correlationId, date)
                .isTrue();
    }

    @Test
    void exportAggregatesConsentAndWatchlist() {
        seedConsent("subject-1");
        seedWatch("subject-1", "AAA.NS");

        // Self-service: callerSub == subjectSub, no admin group required.
        DsrResponse response =
                dsr.apply(new DsrRequest(DsrOperation.EXPORT, "subject-1", "user", "subject-1", "1.2.3.4", "corr-1"));

        assertThat(response).isInstanceOf(UserDataExport.class);
        var export = (UserDataExport) response;
        assertThat(export.watchlist()).contains("AAA.NS");
        // ConsentView accessor is consentGiven(), not given().
        assertThat(export.consent().consentGiven()).isTrue();

        // Task 12: the hashed-subject ACCESS compliance record lands alongside the per-user
        // DATA_EXPORTED record, date-partitioned and carrying no raw sub.
        var complianceItems = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.AUDIT_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk",
                                AttributeValue.builder()
                                        .s("AUDIT#ACCESS#" + LocalDate.now(ZoneOffset.UTC))
                                        .build()))
                        .build())
                .items();
        assertThat(complianceItems).hasSize(1);
        var compliance = complianceItems.get(0);
        assertThat(compliance.get("subjectHash").s()).isNotEqualTo("subject-1");
        assertThat(compliance.get("eventType").s()).isEqualTo("ACCESS");
        assertThat(compliance).doesNotContainKey("email").doesNotContainKey("sourceIp");
        assertThat(compliance.toString()).doesNotContain("subject-1");
    }

    @Test
    void eraseDeletesItemsMirrorsAndPendingAndWritesBothAuditRecords() {
        seedConsent("sub-erase");
        seedWatch("sub-erase", "TCS.NS");

        DsrResponse response =
                dsr.apply(new DsrRequest(DsrOperation.ERASE, "sub-erase", "users", null, "1.2.3.4", "corr-erase"));

        ErasureResult result = (ErasureResult) response;
        assertEquals("erased", result.status());
        assertEquals(3, result.itemsDeleted()); // CONSENT + WATCH#TCS.NS + WATCHSET mirror
        // platform table: CONSENT, WATCH#, WATCHSET mirror, and PROFILE (lease) all gone
        assertNull(getItem("USER#sub-erase", "CONSENT"));
        assertNull(getItem("USER#sub-erase", "WATCH#TCS.NS"));
        assertNull(getItem("WATCHSET", "TICKER#TCS.NS"));
        assertNull(getItem("USER#sub-erase", "PROFILE"));
        // audit table: per-user ACCOUNT_ERASED row keyed by requestedAt#correlationId, plus the
        // hashed compliance row under AUDIT#ERASURE#{date}
        assertAuditEventWritten("USER#sub-erase", "ACCOUNT_ERASED", "corr-erase");
        assertComplianceRowWritten("ERASURE", "corr-erase");
    }

    @Test
    void eraseRerunAfterCompletionIsIdempotent() {
        seedConsent("sub-rerun");
        dsr.apply(new DsrRequest(DsrOperation.ERASE, "sub-rerun", "users", null, null, "corr-a"));

        DsrResponse second = dsr.apply(new DsrRequest(DsrOperation.ERASE, "sub-rerun", "users", null, null, "corr-b"));

        ErasureResult result = (ErasureResult) second;
        assertEquals("erased", result.status()); // lease was cleared by the first run, so re-acquire wins
        assertEquals(1, result.itemsDeleted()); // only the unconditional CONSENT delete write remains
    }

    @Test
    void concurrentDuplicateIsRefusedWhileLeaseHeld() {
        // Seed a fresh lease directly (deletionPending=true, requestedAt=now) and call ERASE: the
        // conditional put must lose and return inProgress without touching the seeded consent row.
        seedConsent("sub-held");
        putProfileLease("sub-held", Instant.now().toString());

        DsrResponse response = dsr.apply(new DsrRequest(DsrOperation.ERASE, "sub-held", "users", null, null, "corr-c"));

        assertEquals("inProgress", ((ErasureResult) response).status());
        assertNotNull(getItem("USER#sub-held", "CONSENT"));
    }

    @Test
    void staleLeaseIsTakenOverAndCascadeCompletes() {
        seedConsent("sub-stale");
        putProfileLease("sub-stale", Instant.now().minus(Duration.ofMinutes(10)).toString());

        DsrResponse response =
                dsr.apply(new DsrRequest(DsrOperation.ERASE, "sub-stale", "users", null, null, "corr-d"));

        assertEquals("erased", ((ErasureResult) response).status());
        assertNull(getItem("USER#sub-stale", "CONSENT"));
        assertNull(getItem("USER#sub-stale", "PROFILE"));
    }
}
