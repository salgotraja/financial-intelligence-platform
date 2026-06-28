package dev.engnotes.consent.model;

/** Consent operation, set by the API Gateway integration template per HTTP method. */
public enum ConsentOperation {
    GRANT,
    VIEW,
    WITHDRAW
}
