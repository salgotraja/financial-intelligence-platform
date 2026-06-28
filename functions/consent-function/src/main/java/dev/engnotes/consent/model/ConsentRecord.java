package dev.engnotes.consent.model;

/** Authoritative consent state read from USER#{sub}/CONSENT. {@code deny()} is the absent default. */
public record ConsentRecord(boolean consentGiven, String version, String purpose, String updatedAt) {

    public static ConsentRecord deny() {
        return new ConsentRecord(false, null, null, null);
    }
}
