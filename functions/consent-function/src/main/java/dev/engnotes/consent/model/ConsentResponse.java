package dev.engnotes.consent.model;

/**
 * Consent output. GRANT returns {@code status=granted}; VIEW returns {@code status=ok}; WITHDRAW
 * returns {@code status=withdrawn}. The remaining fields mirror the resulting consent record.
 */
public record ConsentResponse(String status, boolean consentGiven, String version, String purpose, String updatedAt) {

    public static ConsentResponse of(String status, ConsentRecord record) {
        return new ConsentResponse(
                status, record.consentGiven(), record.version(), record.purpose(), record.updatedAt());
    }
}
