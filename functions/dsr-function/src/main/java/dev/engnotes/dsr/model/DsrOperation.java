package dev.engnotes.dsr.model;

/**
 * DSR operation. {@code EXPORT} and {@code ERASE} are set by the API Gateway integration template per
 * HTTP method (GET -> EXPORT, DELETE -> ERASE); {@code ERASE} now starts the {@code financial-erasure}
 * Step Functions workflow rather than erasing synchronously. The remaining six values are the
 * workflow's per-state discriminators (spec s11, Task 11): each is invoked by a Step Functions
 * {@code LambdaInvoke} task against this same {@code dsr} Lambda, in order:
 * {@code MARK_PENDING -> DELETE_USER_ITEMS -> S3_SAFEGUARD -> DELETE_COGNITO_USER ->
 * SEND_CONFIRMATION_EMAIL -> WRITE_ERASURE_AUDIT}.
 */
public enum DsrOperation {
    EXPORT,
    ERASE,
    MARK_PENDING,
    DELETE_USER_ITEMS,
    S3_SAFEGUARD,
    DELETE_COGNITO_USER,
    SEND_CONFIRMATION_EMAIL,
    WRITE_ERASURE_AUDIT
}
