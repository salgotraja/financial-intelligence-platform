package dev.engnotes.dsr.model;

/**
 * Hashed-subject compliance record type (spec s11, Task 12). Distinct from the per-user operational
 * {@link AuditEventType} records that power {@code /user/export}: these are the permanent,
 * date-partitioned compliance proof, keyed {@code AUDIT#{type}#{yyyy-MM-dd}}, carrying a SHA-256
 * subject hash instead of the raw {@code sub} and no email or other PII.
 */
public enum ComplianceEventType {
    ERASURE,
    ACCESS
}
