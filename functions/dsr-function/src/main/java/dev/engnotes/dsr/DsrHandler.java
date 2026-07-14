package dev.engnotes.dsr;

import dev.engnotes.dsr.auth.SubjectResolver;
import dev.engnotes.dsr.auth.SubjectResolver.Resolution;
import dev.engnotes.dsr.model.AuditEventType;
import dev.engnotes.dsr.model.ComplianceEventType;
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
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * DSR Lambda - Spring Cloud Function entry point (DPDP, spec sub-project C; erasure workflow spec s11,
 * Task 11).
 *
 * <p>One bean {@code dsr} serves both HTTP routes and every {@code financial-erasure} Step Functions
 * state. The API Gateway integration template sets {@code operation} per HTTP method (GET -> EXPORT,
 * DELETE -> ERASE), the caller {@code sub} and comma-joined groups from the authorizer context, and the
 * optional {@code subjectSub} from the {@code ?subjectSub=} query param. Self-service for any group;
 * admin-on-behalf for {@code admins}. Unauthorized calls return a denied shape (200-with-error-body,
 * or 202-with-error-body for ERASE) with no side effects. The erasure workflow's {@code LambdaInvoke}
 * states set {@code operation} to one of {@code MARK_PENDING}, {@code DELETE_USER_ITEMS},
 * {@code S3_SAFEGUARD}, {@code DELETE_COGNITO_USER}, {@code SEND_CONFIRMATION_EMAIL}, or
 * {@code WRITE_ERASURE_AUDIT} directly (no authorizer context: the state machine itself is the trusted
 * caller, already gated by {@code ERASE}'s {@link SubjectResolver} check at start time).
 */
@SpringBootApplication
public class DsrHandler {

    private static final Logger log = LoggerFactory.getLogger(DsrHandler.class);

    public static void main(String[] args) {
        SpringApplication.run(DsrHandler.class, args);
    }

    @Bean
    public Function<DsrRequest, DsrResponse> dsr(
            UserDataExportService export,
            UserErasureService erasure,
            DsrAuditService audit,
            CognitoUserService cognito,
            ErasureWorkflowService workflow,
            ErasureEmailService confirmationEmail,
            Clock clock) {
        return request -> {
            Resolution resolution =
                    SubjectResolver.resolve(request.callerSub(), request.callerGroups(), request.subjectSub());
            log.info(
                    "DSR request. operation={} callerSub={} subjectSub={} allowed={} correlationId={}",
                    request.operation(),
                    request.callerSub(),
                    request.subjectSub(),
                    resolution.allowed(),
                    request.correlationId());

            return switch (request.operation()) {
                case EXPORT -> {
                    if (!resolution.allowed()) {
                        yield UserDataExport.denied();
                    }
                    UserDataExport data = export.export(resolution.subject());
                    audit.record(
                            resolution.subject(),
                            AuditEventType.DATA_EXPORTED,
                            request.callerSub(),
                            request.sourceIp(),
                            request.correlationId());
                    audit.recordCompliance(
                            ComplianceEventType.ACCESS,
                            resolution.subject(),
                            request.callerSub(),
                            Instant.now(clock).toString(),
                            request.correlationId(),
                            null);
                    yield data;
                }

                // Starts the financial-erasure Step Functions workflow instead of erasing
                // synchronously; idempotent via ErasureWorkflowService (already-pending subject is
                // a no-op accepted response, no second execution started).
                case ERASE -> {
                    if (!resolution.allowed()) {
                        yield ErasureAcceptance.denied();
                    }
                    yield workflow.startErasure(
                            resolution.subject(), request.callerSub(), request.sourceIp(), request.correlationId());
                }

                // Captures the subject's email from Cognito before any delete, so
                // SendConfirmationEmail can send after DeleteCognitoUser removes the identity. Emits ""
                // rather than null when no Cognito user/email exists: the ASL payload template's
                // "email.$": "$.email" on every downstream state requires the path to resolve, and an
                // absent path (rather than a null value) fails the execution terminally if serialization
                // ever drops null fields. sendConfirmation already no-ops on a blank address.
                case MARK_PENDING -> {
                    erasure.setDeletionPending(request.subjectSub());
                    String email = cognito.findEmailBySub(request.subjectSub());
                    yield new ErasureStepResult(
                            request.subjectSub(),
                            request.callerSub(),
                            request.sourceIp(),
                            request.correlationId(),
                            request.requestedAt(),
                            email == null ? "" : email,
                            null,
                            null,
                            null,
                            null);
                }

                case DELETE_USER_ITEMS -> {
                    int itemsDeleted = erasure.deleteUserItems(request.subjectSub());
                    yield new ErasureStepResult(
                            request.subjectSub(),
                            request.callerSub(),
                            request.sourceIp(),
                            request.correlationId(),
                            request.requestedAt(),
                            request.email(),
                            itemsDeleted,
                            null,
                            null,
                            null);
                }

                case S3_SAFEGUARD -> {
                    erasure.s3Safeguard(request.subjectSub());
                    yield new ErasureStepResult(
                            request.subjectSub(),
                            request.callerSub(),
                            request.sourceIp(),
                            request.correlationId(),
                            request.requestedAt(),
                            request.email(),
                            null,
                            null,
                            null,
                            null);
                }

                case DELETE_COGNITO_USER -> {
                    boolean deleted = cognito.deleteBySub(request.subjectSub());
                    yield new ErasureStepResult(
                            request.subjectSub(),
                            request.callerSub(),
                            request.sourceIp(),
                            request.correlationId(),
                            request.requestedAt(),
                            request.email(),
                            null,
                            deleted,
                            null,
                            null);
                }

                // A failure here (including SES sandbox rejection) propagates as an exception; the
                // state machine's own retry-then-catch decides whether erasure still completes.
                case SEND_CONFIRMATION_EMAIL -> {
                    confirmationEmail.sendConfirmation(request.email(), request.subjectSub(), request.requestedAt());
                    yield new ErasureStepResult(
                            request.subjectSub(),
                            request.callerSub(),
                            request.sourceIp(),
                            request.correlationId(),
                            request.requestedAt(),
                            request.email(),
                            null,
                            null,
                            true,
                            null);
                }

                // Last state: clears the deletion-pending gate and writes the ACCOUNT_ERASED audit
                // record with request + completion timestamps and the emailSent flag (false when
                // SendConfirmationEmail's catch routed here instead).
                case WRITE_ERASURE_AUDIT -> {
                    erasure.clearDeletionPending(request.subjectSub());
                    boolean emailSent = Boolean.TRUE.equals(request.emailSent());
                    String completedAt = Instant.now(clock).toString();
                    audit.recordErasureCompletion(
                            request.subjectSub(),
                            request.callerSub(),
                            request.sourceIp(),
                            request.correlationId(),
                            request.requestedAt(),
                            completedAt,
                            emailSent);
                    audit.recordCompliance(
                            ComplianceEventType.ERASURE,
                            request.subjectSub(),
                            request.callerSub(),
                            request.requestedAt(),
                            request.correlationId(),
                            emailSent);
                    yield new ErasureStepResult(
                            request.subjectSub(),
                            request.callerSub(),
                            request.sourceIp(),
                            request.correlationId(),
                            request.requestedAt(),
                            request.email(),
                            null,
                            null,
                            emailSent,
                            completedAt);
                }
            };
        };
    }
}
