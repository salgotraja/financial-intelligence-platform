package dev.engnotes.dsr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.dsr.model.AuditEventType;
import dev.engnotes.dsr.model.DsrOperation;
import dev.engnotes.dsr.model.DsrRequest;
import dev.engnotes.dsr.model.DsrResponse;
import dev.engnotes.dsr.model.ErasureAcceptance;
import dev.engnotes.dsr.model.ErasureStepResult;
import dev.engnotes.dsr.model.UserDataExport;
import dev.engnotes.dsr.service.CognitoUserService;
import dev.engnotes.dsr.service.DsrAuditService;
import dev.engnotes.dsr.service.ErasureEmailService;
import dev.engnotes.dsr.service.ErasureWorkflowService;
import dev.engnotes.dsr.service.UserDataExportService;
import dev.engnotes.dsr.service.UserErasureService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DsrHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-14T10:00:00Z");

    @Mock
    private UserDataExportService export;

    @Mock
    private UserErasureService erasure;

    @Mock
    private DsrAuditService audit;

    @Mock
    private CognitoUserService cognito;

    @Mock
    private ErasureWorkflowService workflow;

    @Mock
    private ErasureEmailService confirmationEmail;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private DsrResponse handle(DsrRequest request) {
        return new DsrHandler()
                .dsr(export, erasure, audit, cognito, workflow, confirmationEmail, clock)
                .apply(request);
    }

    @Test
    void exportReadsThenAuditsForSelfService() {
        when(export.export("user-1")).thenReturn(new UserDataExport("ok", "user-1", null, List.of(), List.of()));

        DsrResponse response =
                handle(new DsrRequest(DsrOperation.EXPORT, "user-1", "readers", null, "1.2.3.4", "corr-1"));

        InOrder order = inOrder(export, audit);
        order.verify(export).export("user-1");
        order.verify(audit).record("user-1", AuditEventType.DATA_EXPORTED, "user-1", "1.2.3.4", "corr-1");
        assertThat(((UserDataExport) response).status()).isEqualTo("ok");
    }

    @Test
    void eraseStartsWorkflowForSelfService() {
        when(workflow.startErasure("user-2", "user-2", "9.9.9.9", "corr-2"))
                .thenReturn(ErasureAcceptance.started("user-2", "arn:aws:states:...:execution:x"));

        DsrResponse response =
                handle(new DsrRequest(DsrOperation.ERASE, "user-2", "readers", null, "9.9.9.9", "corr-2"));

        ErasureAcceptance accepted = (ErasureAcceptance) response;
        assertThat(accepted.status()).isEqualTo("accepted");
        assertThat(accepted.executionArn()).isEqualTo("arn:aws:states:...:execution:x");
        verifyNoInteractions(erasure, audit, cognito, confirmationEmail);
    }

    @Test
    void eraseAdminOnBehalfStartsWorkflowForTargetSubject() {
        when(workflow.startErasure("user-3", "admin-1", "9.9.9.9", "corr-3"))
                .thenReturn(ErasureAcceptance.alreadyPending("user-3"));

        DsrResponse response =
                handle(new DsrRequest(DsrOperation.ERASE, "admin-1", "premium,admins", "user-3", "9.9.9.9", "corr-3"));

        ErasureAcceptance accepted = (ErasureAcceptance) response;
        assertThat(accepted.status()).isEqualTo("accepted");
        assertThat(accepted.executionArn()).isNull();
    }

    @Test
    void nonAdminTargetingAnotherSubjectIsDeniedWithNoSideEffects() {
        DsrResponse response =
                handle(new DsrRequest(DsrOperation.ERASE, "user-1", "premium", "user-2", "1.1.1.1", "corr-3"));

        assertThat(((ErasureAcceptance) response).status()).isEqualTo("denied");
        verifyNoInteractions(workflow, erasure, audit, cognito, confirmationEmail);
    }

    @Test
    void blankCallerIsDeniedForExport() {
        DsrResponse response = handle(new DsrRequest(DsrOperation.EXPORT, "", "admins", null, "1.1.1.1", "corr-4"));

        assertThat(((UserDataExport) response).status()).isEqualTo("denied");
        verify(export, never()).export(anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void markPendingSetsFlagAndCapturesEmail() {
        when(cognito.findEmailBySub("user-4")).thenReturn("user4@example.com");

        DsrResponse response = handle(new DsrRequest(
                DsrOperation.MARK_PENDING, null, null, "user-4", null, "corr-5", "2026-07-14T09:00:00Z", null, null));

        InOrder order = inOrder(erasure, cognito);
        order.verify(erasure).setDeletionPending("user-4");
        order.verify(cognito).findEmailBySub("user-4");
        ErasureStepResult result = (ErasureStepResult) response;
        assertThat(result.subjectSub()).isEqualTo("user-4");
        assertThat(result.email()).isEqualTo("user4@example.com");
        assertThat(result.requestedAt()).isEqualTo("2026-07-14T09:00:00Z");
    }

    @Test
    void deleteUserItemsDispatchesToErasureServiceAndEchoesEmail() {
        when(erasure.deleteUserItems("user-5")).thenReturn(3);

        DsrResponse response = handle(new DsrRequest(
                DsrOperation.DELETE_USER_ITEMS,
                null,
                null,
                "user-5",
                null,
                "corr-6",
                "2026-07-14T09:00:00Z",
                "user5@example.com",
                null));

        ErasureStepResult result = (ErasureStepResult) response;
        assertThat(result.itemsDeleted()).isEqualTo(3);
        assertThat(result.email()).isEqualTo("user5@example.com");
    }

    @Test
    void s3SafeguardDispatchesNoOpAndEchoesContext() {
        DsrResponse response = handle(new DsrRequest(
                DsrOperation.S3_SAFEGUARD,
                null,
                null,
                "user-6",
                null,
                "corr-7",
                "2026-07-14T09:00:00Z",
                "user6@example.com",
                null));

        verify(erasure).s3Safeguard("user-6");
        ErasureStepResult result = (ErasureStepResult) response;
        assertThat(result.email()).isEqualTo("user6@example.com");
        verifyNoInteractions(cognito, confirmationEmail, audit);
    }

    @Test
    void deleteCognitoUserDispatchesToCognitoService() {
        when(cognito.deleteBySub("user-7")).thenReturn(true);

        DsrResponse response = handle(new DsrRequest(
                DsrOperation.DELETE_COGNITO_USER, null, null, "user-7", null, "corr-8", null, null, null));

        ErasureStepResult result = (ErasureStepResult) response;
        assertThat(result.cognitoUserDeleted()).isTrue();
    }

    @Test
    void sendConfirmationEmailDispatchesAndMarksSent() {
        DsrResponse response = handle(new DsrRequest(
                DsrOperation.SEND_CONFIRMATION_EMAIL,
                null,
                null,
                "user-8",
                null,
                "corr-9",
                "2026-07-14T09:00:00Z",
                "user8@example.com",
                null));

        verify(confirmationEmail).sendConfirmation("user8@example.com", "user-8", "2026-07-14T09:00:00Z");
        ErasureStepResult result = (ErasureStepResult) response;
        assertThat(result.emailSent()).isTrue();
    }

    // A send failure must propagate out of the Lambda invocation uncaught: the state machine's own
    // retry-then-catch (not this bean) is what lets erasure still complete with emailSent=false.
    @Test
    void sendConfirmationEmailFailurePropagatesUncaught() {
        org.mockito.Mockito.doThrow(new RuntimeException("SES sandbox rejection"))
                .when(confirmationEmail)
                .sendConfirmation("user9@example.com", "user-9", "2026-07-14T09:00:00Z");

        assertThatThrownBy(() -> handle(new DsrRequest(
                        DsrOperation.SEND_CONFIRMATION_EMAIL,
                        null,
                        null,
                        "user-9",
                        null,
                        "corr-10",
                        "2026-07-14T09:00:00Z",
                        "user9@example.com",
                        null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("SES sandbox rejection");
    }

    @Test
    void writeErasureAuditClearsPendingAndRecordsEmailSentFalseWhenAbsent() {
        DsrResponse response = handle(new DsrRequest(
                DsrOperation.WRITE_ERASURE_AUDIT,
                "admin-1",
                null,
                "user-10",
                "1.2.3.4",
                "corr-11",
                "2026-07-14T09:00:00Z",
                null,
                null));

        verify(erasure).clearDeletionPending("user-10");
        verify(audit)
                .recordErasureCompletion(
                        "user-10", "admin-1", "1.2.3.4", "corr-11", "2026-07-14T09:00:00Z", NOW.toString(), false);
        ErasureStepResult result = (ErasureStepResult) response;
        assertThat(result.emailSent()).isFalse();
        assertThat(result.completedAt()).isEqualTo(NOW.toString());
    }

    @Test
    void writeErasureAuditRecordsEmailSentTrueWhenSet() {
        handle(new DsrRequest(
                DsrOperation.WRITE_ERASURE_AUDIT,
                "user-11",
                null,
                "user-11",
                null,
                "corr-12",
                "2026-07-14T09:00:00Z",
                null,
                true));

        verify(audit)
                .recordErasureCompletion(
                        "user-11", "user-11", null, "corr-12", "2026-07-14T09:00:00Z", NOW.toString(), true);
    }
}
