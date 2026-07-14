package dev.engnotes.dsr.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.dsr.model.DsrOperation;
import dev.engnotes.dsr.model.DsrRequest;
import dev.engnotes.dsr.model.DsrResponse;
import dev.engnotes.dsr.model.ErasureStepResult;
import dev.engnotes.dsr.model.UserDataExport;
import dev.engnotes.dsr.service.CognitoUserService;
import dev.engnotes.dsr.service.ErasureEmailService;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Covers the erasure workflow's decomposed operations (spec s11, Task 11) by invoking the {@code dsr}
 * bean stepwise, in the same order the {@code financial-erasure} state machine would: MARK_PENDING,
 * DELETE_USER_ITEMS, S3_SAFEGUARD, DELETE_COGNITO_USER, SEND_CONFIRMATION_EMAIL, WRITE_ERASURE_AUDIT.
 *
 * <p>Not covered here (LocalStack Community has neither Step Functions nor SES nor Cognito):
 * the state machine itself (StartExecution, its own retry/Catch transitions, DELETE /user/account's
 * 202 response) - {@link dev.engnotes.dsr.service.ErasureWorkflowServiceTest} unit-tests StartExecution
 * against a mocked SfnClient instead, and the state machine's structure/retry/catch wiring is asserted
 * by the CDK tests in {@code infrastructure}'s QueryStackTest. Cognito and SES are mocked below, same
 * as the pre-existing EXPORT/ERASE coverage already mocked Cognito.
 */
@SpringBootTest
class DsrBeanIT extends AbstractLocalStackIT {

    // Cognito is LocalStack-Pro-only; mock the service so erasure completes without a real pool.
    @MockitoBean
    CognitoUserService cognitoUserService;

    // SES is not in this LocalStack container's SERVICES list either; mock the send.
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

    private boolean itemExists(String table, String pk, String sk) {
        return dynamoDbClient
                .getItem(GetItemRequest.builder()
                        .tableName(table)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(pk).build(),
                                "SK", AttributeValue.builder().s(sk).build()))
                        .build())
                .hasItem();
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
    }

    @Test
    void erasureWorkflowOperationsRunStepwiseAndCompleteWithAnAuditRecord() {
        when(cognitoUserService.findEmailBySub(ArgumentMatchers.eq("subject-2")))
                .thenReturn("subject2@example.com");
        when(cognitoUserService.deleteBySub(ArgumentMatchers.eq("subject-2"))).thenReturn(true);
        seedConsent("subject-2");
        seedWatch("subject-2", "BBB.NS");
        String requestedAt = "2026-07-14T09:00:00Z";

        var marked = (ErasureStepResult) dsr.apply(new DsrRequest(
                DsrOperation.MARK_PENDING, null, null, "subject-2", null, "corr-mp", requestedAt, null, null));
        assertThat(marked.email()).isEqualTo("subject2@example.com");
        assertThat(itemExists(PlatformSchema.PLATFORM_TABLE, "USER#subject-2", "PROFILE"))
                .isTrue();

        var deleted = (ErasureStepResult) dsr.apply(new DsrRequest(
                DsrOperation.DELETE_USER_ITEMS,
                null,
                null,
                "subject-2",
                null,
                "corr-du",
                requestedAt,
                marked.email(),
                null));
        assertThat(deleted.itemsDeleted()).isEqualTo(3); // CONSENT + WATCH# + WATCHSET mirror
        assertThat(itemExists(PlatformSchema.PLATFORM_TABLE, "USER#subject-2", "CONSENT"))
                .isFalse();

        dsr.apply(new DsrRequest(
                DsrOperation.S3_SAFEGUARD,
                null,
                null,
                "subject-2",
                null,
                "corr-s3",
                requestedAt,
                deleted.email(),
                null));

        var cognitoDeleted = (ErasureStepResult) dsr.apply(new DsrRequest(
                DsrOperation.DELETE_COGNITO_USER,
                null,
                null,
                "subject-2",
                null,
                "corr-dc",
                requestedAt,
                deleted.email(),
                null));
        assertThat(cognitoDeleted.cognitoUserDeleted()).isTrue();

        var emailResult = (ErasureStepResult) dsr.apply(new DsrRequest(
                DsrOperation.SEND_CONFIRMATION_EMAIL,
                null,
                null,
                "subject-2",
                null,
                "corr-se",
                requestedAt,
                cognitoDeleted.email(),
                null));
        assertThat(emailResult.emailSent()).isTrue();
        verify(confirmationEmail).sendConfirmation("subject2@example.com", "subject-2", requestedAt);

        var audited = (ErasureStepResult) dsr.apply(new DsrRequest(
                DsrOperation.WRITE_ERASURE_AUDIT,
                "admin-caller",
                null,
                "subject-2",
                "1.2.3.4",
                "corr-wa",
                requestedAt,
                null,
                emailResult.emailSent()));
        assertThat(audited.completedAt()).isNotNull();
        assertThat(itemExists(PlatformSchema.PLATFORM_TABLE, "USER#subject-2", "PROFILE"))
                .isFalse();

        var auditItems = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.AUDIT_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk",
                                AttributeValue.builder().s("USER#subject-2").build()))
                        .build())
                .items();
        assertThat(auditItems).hasSize(1);
        var event = auditItems.get(0);
        assertThat(event.get("eventType").s()).isEqualTo("ACCOUNT_ERASED");
        assertThat(event.get("actorSub").s()).isEqualTo("admin-caller");
        assertThat(event.get("requestedAt").s()).isEqualTo(requestedAt);
        assertThat(event.get("completedAt").s()).isEqualTo(audited.completedAt());
        assertThat(event.get("emailSent").bool()).isTrue();
    }

    // Simulates the state machine's Catch -> EmailFailed -> WriteErasureAudit path: SEND_CONFIRMATION_EMAIL
    // must propagate the failure uncaught (proven here against the real dsr bean, not just a mock in
    // DsrHandlerTest), and WriteErasureAudit given emailSent=false must still complete and record it.
    @Test
    void emailFailurePropagatesButErasureStillCompletesWithEmailSentFalse() {
        String requestedAt = "2026-07-14T09:00:00Z";
        doThrow(new RuntimeException("SES sandbox rejection"))
                .when(confirmationEmail)
                .sendConfirmation("subject3@example.com", "subject-3", requestedAt);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dsr.apply(new DsrRequest(
                        DsrOperation.SEND_CONFIRMATION_EMAIL,
                        null,
                        null,
                        "subject-3",
                        null,
                        "corr-se",
                        requestedAt,
                        "subject3@example.com",
                        null)))
                .isInstanceOf(RuntimeException.class);

        var audited = (ErasureStepResult) dsr.apply(new DsrRequest(
                DsrOperation.WRITE_ERASURE_AUDIT,
                "subject-3",
                null,
                "subject-3",
                null,
                "corr-wa",
                requestedAt,
                null,
                false));
        assertThat(audited.emailSent()).isFalse();

        var auditItems = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.AUDIT_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk",
                                AttributeValue.builder().s("USER#subject-3").build()))
                        .build())
                .items();
        assertThat(auditItems).hasSize(1);
        assertThat(auditItems.get(0).get("emailSent").bool()).isFalse();
    }
}
