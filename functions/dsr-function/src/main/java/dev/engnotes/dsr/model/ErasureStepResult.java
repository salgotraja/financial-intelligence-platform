package dev.engnotes.dsr.model;

/**
 * Output of one erasure-workflow state (spec s11, Task 11): {@code MARK_PENDING},
 * {@code DELETE_USER_ITEMS}, {@code S3_SAFEGUARD}, {@code DELETE_COGNITO_USER},
 * {@code SEND_CONFIRMATION_EMAIL}, or {@code WRITE_ERASURE_AUDIT}. Every field the running Step
 * Functions execution has accumulated so far is present, not just the state's own contribution: each
 * {@code LambdaInvoke} task uses {@code payloadResponseOnly}, so this record's JSON fully replaces the
 * state machine's working data on every hop, and the next state's payload reads its inputs back off
 * this same shape. A handler only fills in the field(s) it computed and echoes the rest from the
 * {@link DsrRequest} it received.
 */
public record ErasureStepResult(
        String subjectSub,
        String callerSub,
        String sourceIp,
        String correlationId,
        String requestedAt,
        String email,
        Integer itemsDeleted,
        Boolean cognitoUserDeleted,
        Boolean emailSent,
        String completedAt)
        implements DsrResponse {}
