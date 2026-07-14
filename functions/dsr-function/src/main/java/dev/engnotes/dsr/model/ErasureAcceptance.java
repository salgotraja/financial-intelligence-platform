package dev.engnotes.dsr.model;

/**
 * Response to {@code DELETE /user/account} (spec s11, Task 11): the route starts the
 * {@code financial-erasure} Step Functions workflow instead of erasing synchronously, so the API
 * returns 202 with {@code status=accepted} and, when a new execution was actually started,
 * {@code executionArn}. {@code executionArn} is {@code null} both when denied and on the idempotent
 * no-op (subject already deletion-pending): no second execution is started for a pending subject.
 */
public record ErasureAcceptance(String status, String subjectSub, String executionArn) implements DsrResponse {

    public static ErasureAcceptance denied() {
        return new ErasureAcceptance("denied", null, null);
    }

    public static ErasureAcceptance alreadyPending(String subjectSub) {
        return new ErasureAcceptance("accepted", subjectSub, null);
    }

    public static ErasureAcceptance started(String subjectSub, String executionArn) {
        return new ErasureAcceptance("accepted", subjectSub, executionArn);
    }
}
