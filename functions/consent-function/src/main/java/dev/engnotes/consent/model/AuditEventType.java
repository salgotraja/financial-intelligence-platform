package dev.engnotes.consent.model;

/** Append-only consent lifecycle event types written to the audit table. */
public enum AuditEventType {
    ACCOUNT_CREATED,
    CONSENT_GRANTED,
    CONSENT_WITHDRAWN,
    CONSENT_RECONSENT_REQUIRED
}
