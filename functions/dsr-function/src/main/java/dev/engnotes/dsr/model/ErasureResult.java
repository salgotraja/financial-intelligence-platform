package dev.engnotes.dsr.model;

/**
 * Outcome of an erasure. {@code status} is {@code erased} on success or {@code denied} when
 * authorization fails. {@code itemsDeleted} counts platform-table items removed; {@code cognitoUserDeleted}
 * is true when a matching Cognito user was found and deleted.
 */
public record ErasureResult(String status, String subjectSub, int itemsDeleted, boolean cognitoUserDeleted)
        implements DsrResponse {

    public static ErasureResult denied() {
        return new ErasureResult("denied", null, 0, false);
    }
}
