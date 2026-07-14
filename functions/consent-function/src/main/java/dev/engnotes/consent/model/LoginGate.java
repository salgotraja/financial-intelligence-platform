package dev.engnotes.consent.model;

/**
 * PreAuthentication login-gate decision (spec s11, adapted to the shipped consent model). PENDING
 * (never consented) and GIVEN-under-the-current-policy-version both allow login; WITHDRAWN and
 * GIVEN-under-a-stale-version deny.
 */
public enum LoginGate {
    ALLOWED,
    WITHDRAWN,
    RECONSENT_REQUIRED
}
