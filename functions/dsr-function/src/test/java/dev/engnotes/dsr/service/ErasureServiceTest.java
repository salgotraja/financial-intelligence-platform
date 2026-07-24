package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.engnotes.dsr.model.ComplianceEventType;
import dev.engnotes.dsr.model.ErasureResult;
import dev.engnotes.observability.Metrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ErasureServiceTest {

    private static final String NOW = "2026-07-19T10:00:00Z";

    private UserErasureService erasure;
    private CognitoUserService cognito;
    private ErasureEmailService email;
    private DsrAuditService audit;
    private Metrics.Capture capture;
    private ErasureService service;

    @BeforeEach
    void setUp() {
        erasure = mock(UserErasureService.class);
        cognito = mock(CognitoUserService.class);
        email = mock(ErasureEmailService.class);
        audit = mock(DsrAuditService.class);
        capture = Metrics.forTesting();
        service = new ErasureService(
                erasure, cognito, email, audit, Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC), capture.metrics());
    }

    @Test
    void happyPathRunsCascadeInOrderAndReportsErased() {
        when(erasure.acquireDeletionLease("sub-1", NOW)).thenReturn(true);
        when(cognito.findEmailBySub("sub-1")).thenReturn("user@example.com");
        when(erasure.deleteUserItems("sub-1")).thenReturn(5);
        when(cognito.deleteBySub("sub-1")).thenReturn(true);

        ErasureResult result = service.erase("sub-1", "sub-1", "1.2.3.4", "corr-1");

        assertEquals(new ErasureResult("erased", "sub-1", 5, true, true, NOW, NOW), result);
        InOrder inOrder = inOrder(erasure, cognito, email, audit);
        inOrder.verify(erasure).acquireDeletionLease("sub-1", NOW);
        inOrder.verify(cognito).findEmailBySub("sub-1"); // email captured before the identity dies
        inOrder.verify(erasure).deleteUserItems("sub-1");
        inOrder.verify(erasure).s3Safeguard("sub-1");
        inOrder.verify(cognito).deleteBySub("sub-1");
        inOrder.verify(email).sendConfirmation("user@example.com", "sub-1", NOW);
        inOrder.verify(erasure).clearDeletionPending("sub-1");
        inOrder.verify(audit).recordErasureCompletion("sub-1", "sub-1", "1.2.3.4", "corr-1", NOW, NOW, true);
        inOrder.verify(audit).recordCompliance(ComplianceEventType.ERASURE, "sub-1", "sub-1", NOW, "corr-1", true);
        assertThat(capture.records()).anySatisfy(record -> assertThat(record).contains("\"ErasureCompleted\""));
        assertThat(capture.records()).noneSatisfy(record -> assertThat(record).contains("\"ErasureLeaseRefused\""));
    }

    @Test
    void leaseLostReturnsInProgressWithNoWork() {
        when(erasure.acquireDeletionLease("sub-1", NOW)).thenReturn(false);

        ErasureResult result = service.erase("sub-1", "sub-1", "1.2.3.4", "corr-1");

        assertEquals(ErasureResult.inProgress("sub-1"), result);
        verify(erasure, never()).deleteUserItems(any());
        verify(cognito, never()).deleteBySub(any());
        verifyNoInteractions(audit);
        assertThat(capture.records()).anySatisfy(record -> assertThat(record).contains("\"ErasureLeaseRefused\""));
        assertThat(capture.records()).noneSatisfy(record -> assertThat(record).contains("\"ErasureCompleted\""));
    }

    @Test
    void emailFailureFoldsToEmailSentFalseAndErasureStillCompletes() {
        when(erasure.acquireDeletionLease("sub-1", NOW)).thenReturn(true);
        when(cognito.findEmailBySub("sub-1")).thenReturn("user@example.com");
        when(erasure.deleteUserItems("sub-1")).thenReturn(3);
        when(cognito.deleteBySub("sub-1")).thenReturn(true);
        doThrow(new RuntimeException("ses sandbox")).when(email).sendConfirmation(any(), any(), any());

        ErasureResult result = service.erase("sub-1", "admin-1", "1.2.3.4", "corr-1");

        assertEquals("erased", result.status());
        assertFalse(result.emailSent());
        verify(erasure).clearDeletionPending("sub-1");
        verify(audit).recordErasureCompletion("sub-1", "admin-1", "1.2.3.4", "corr-1", NOW, NOW, false);
        verify(audit).recordCompliance(ComplianceEventType.ERASURE, "sub-1", "admin-1", NOW, "corr-1", false);
    }

    @Test
    void missingCognitoEmailMeansEmailSentFalseWithoutSendAttempt() {
        when(erasure.acquireDeletionLease("sub-1", NOW)).thenReturn(true);
        when(cognito.findEmailBySub("sub-1")).thenReturn(null);
        when(erasure.deleteUserItems("sub-1")).thenReturn(1);
        when(cognito.deleteBySub("sub-1")).thenReturn(false);

        ErasureResult result = service.erase("sub-1", "sub-1", null, "corr-1");

        assertEquals("erased", result.status());
        assertFalse(result.emailSent());
        assertFalse(result.cognitoUserDeleted());
        verifyNoInteractions(email);
    }

    @Test
    void midCascadeFailurePropagatesAndLeavesPendingSet() {
        when(erasure.acquireDeletionLease("sub-1", NOW)).thenReturn(true);
        when(cognito.findEmailBySub("sub-1")).thenReturn("user@example.com");
        when(erasure.deleteUserItems("sub-1")).thenThrow(new RuntimeException("dynamo down"));

        assertThrows(RuntimeException.class, () -> service.erase("sub-1", "sub-1", "1.2.3.4", "corr-1"));

        verify(erasure, never()).clearDeletionPending(any());
        verifyNoInteractions(audit);
    }
}
