package dev.engnotes.dsr.model;

/**
 * DSR audit event types appended to the shared audit table. A deliberate small duplicate of
 * consent-function's enum (independent-module pattern); this module only emits these two.
 */
public enum AuditEventType {
    DATA_EXPORTED,
    ACCOUNT_ERASED
}
