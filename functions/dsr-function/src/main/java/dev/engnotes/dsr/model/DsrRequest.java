package dev.engnotes.dsr.model;

/**
 * DSR input. {@code operation} is set by the API Gateway template per HTTP method. {@code callerSub}
 * and {@code callerGroups} (comma-joined) come from the authorizer context, never the body.
 * {@code subjectSub} is the optional {@code ?subjectSub=} query param (admin-on-behalf). {@code sourceIp}
 * is $context.identity.sourceIp; {@code correlationId} is $context.requestId, used as the audit seq.
 */
public record DsrRequest(
        DsrOperation operation,
        String callerSub,
        String callerGroups,
        String subjectSub,
        String sourceIp,
        String correlationId) {}
