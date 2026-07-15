package dev.engnotes.dsr.model;

/**
 * DSR input. {@code operation} is set by the API Gateway template per HTTP method. {@code callerSub}
 * and {@code callerGroups} (comma-joined) come from the authorizer context, never the body.
 * {@code subjectSub} is the optional {@code ?subjectSub=} query param (admin-on-behalf). {@code sourceIp}
 * is $context.identity.sourceIp; {@code correlationId} is $context.requestId, used as the audit seq.
 *
 * <p>{@code requestedAt}, {@code email}, and {@code emailSent} are unused by EXPORT/ERASE (API Gateway
 * never sets them) and exist only for the erasure workflow's per-state {@code LambdaInvoke} payloads
 * (spec s11, Task 11): each state's {@code payloadResponseOnly} output fully replaces the running
 * state-machine JSON, so every workflow operation echoes these forward alongside its own contribution.
 * The six-argument constructor preserves every pre-workflow call site.
 */
public record DsrRequest(
        DsrOperation operation,
        String callerSub,
        String callerGroups,
        String subjectSub,
        String sourceIp,
        String correlationId,
        String requestedAt,
        String email,
        Boolean emailSent) {

    public DsrRequest(
            DsrOperation operation,
            String callerSub,
            String callerGroups,
            String subjectSub,
            String sourceIp,
            String correlationId) {
        this(operation, callerSub, callerGroups, subjectSub, sourceIp, correlationId, null, null, null);
    }
}
