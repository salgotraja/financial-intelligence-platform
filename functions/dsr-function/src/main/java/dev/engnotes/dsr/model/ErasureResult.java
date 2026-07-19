package dev.engnotes.dsr.model;

/**
 * Outcome of a synchronous erasure (Step Functions workflow collapsed 2026-07-19). {@code status} is
 * {@code erased} on success, {@code inProgress} when a concurrent cascade holds the deletion lease
 * (no work done), or {@code denied} when authorization fails. {@code emailSent} is true only when a
 * confirmation address existed and the SES send succeeded; {@code requestedAt}/{@code completedAt}
 * bracket the cascade.
 */
public record ErasureResult(
        String status,
        String subjectSub,
        int itemsDeleted,
        boolean cognitoUserDeleted,
        boolean emailSent,
        String requestedAt,
        String completedAt)
        implements DsrResponse {

    public static ErasureResult denied() {
        return new ErasureResult("denied", null, 0, false, false, null, null);
    }

    public static ErasureResult inProgress(String subjectSub) {
        return new ErasureResult("inProgress", subjectSub, 0, false, false, null, null);
    }
}
