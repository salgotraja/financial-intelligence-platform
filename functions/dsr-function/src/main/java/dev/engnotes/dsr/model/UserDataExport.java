package dev.engnotes.dsr.model;

import java.util.List;

/**
 * Inline export of a subject's personal data. {@code status} is {@code ok} on success or {@code denied}
 * when authorization fails (200-with-error-body convention).
 */
public record UserDataExport(
        String status, String subjectSub, ConsentView consent, List<String> watchlist, List<AuditEventView> auditTrail)
        implements DsrResponse {

    public static UserDataExport denied() {
        return new UserDataExport("denied", null, null, List.of(), List.of());
    }
}
