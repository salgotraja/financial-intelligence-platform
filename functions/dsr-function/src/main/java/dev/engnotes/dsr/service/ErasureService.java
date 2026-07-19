package dev.engnotes.dsr.service;

import dev.engnotes.dsr.model.ComplianceEventType;
import dev.engnotes.dsr.model.ErasureResult;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Synchronous right-to-erasure cascade, replacing the {@code financial-erasure} Step Functions
 * workflow (collapsed 2026-07-19): seven linear states invoking this same Lambda bought no fan-out
 * and no differential retry over a plain method call. Order is unchanged: acquire the deletion
 * lease, capture the subject's email before the identity is deleted, reversible DynamoDB deletes,
 * the irreversible Cognito delete, confirmation email (failure folds to {@code emailSent=false},
 * never fails the erasure), then clear the lease and write both audit records last so they document
 * a completed erasure. Every step is idempotent and re-runnable: a mid-cascade failure propagates,
 * the lease stays set (still blocking watchlist/consent writes), and a repeat request after the
 * 5-minute lease finishes the remainder.
 */
@Service
public class ErasureService {

    private static final Logger log = LoggerFactory.getLogger(ErasureService.class);

    private final UserErasureService erasure;
    private final CognitoUserService cognito;
    private final ErasureEmailService confirmationEmail;
    private final DsrAuditService audit;
    private final Clock clock;

    public ErasureService(
            UserErasureService erasure,
            CognitoUserService cognito,
            ErasureEmailService confirmationEmail,
            DsrAuditService audit,
            Clock clock) {
        this.erasure = erasure;
        this.cognito = cognito;
        this.confirmationEmail = confirmationEmail;
        this.audit = audit;
        this.clock = clock;
    }

    public ErasureResult erase(String subjectSub, String callerSub, String sourceIp, String correlationId) {
        String requestedAt = Instant.now(clock).toString();
        if (!erasure.acquireDeletionLease(subjectSub, requestedAt)) {
            return ErasureResult.inProgress(subjectSub);
        }

        String email = cognito.findEmailBySub(subjectSub);
        int itemsDeleted = erasure.deleteUserItems(subjectSub);
        erasure.s3Safeguard(subjectSub);
        boolean cognitoUserDeleted = cognito.deleteBySub(subjectSub);

        boolean emailSent = false;
        if (email != null && !email.isBlank()) {
            try {
                confirmationEmail.sendConfirmation(email, subjectSub, requestedAt);
                emailSent = true;
            } catch (RuntimeException e) {
                log.warn("Erasure confirmation email failed; erasure still completes. subjectSub={}", subjectSub, e);
            }
        }

        erasure.clearDeletionPending(subjectSub);
        String completedAt = Instant.now(clock).toString();
        audit.recordErasureCompletion(
                subjectSub, callerSub, sourceIp, correlationId, requestedAt, completedAt, emailSent);
        audit.recordCompliance(
                ComplianceEventType.ERASURE, subjectSub, callerSub, requestedAt, correlationId, emailSent);
        return new ErasureResult(
                "erased", subjectSub, itemsDeleted, cognitoUserDeleted, emailSent, requestedAt, completedAt);
    }
}
