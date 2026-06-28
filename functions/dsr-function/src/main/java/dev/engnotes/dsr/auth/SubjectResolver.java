package dev.engnotes.dsr.auth;

import java.util.Arrays;

/**
 * Resolves which subject a DSR call acts on. Self-service for any authenticated caller on their own
 * {@code sub}; admin-on-behalf for callers in the {@code admins} group targeting another {@code sub}.
 * A blank caller {@code sub} is denied (DPDP endpoints require an authenticated principal).
 */
public final class SubjectResolver {

    private static final String ADMIN_GROUP = "admins";

    public record Resolution(boolean allowed, String subject) {
        static Resolution deny() {
            return new Resolution(false, null);
        }

        static Resolution allow(String subject) {
            return new Resolution(true, subject);
        }
    }

    private SubjectResolver() {}

    public static Resolution resolve(String callerSub, String callerGroups, String subjectSub) {
        if (callerSub == null || callerSub.isBlank()) {
            return Resolution.deny();
        }
        boolean targetingOther = subjectSub != null && !subjectSub.isBlank() && !subjectSub.equals(callerSub);
        if (!targetingOther) {
            return Resolution.allow(callerSub);
        }
        return isAdmin(callerGroups) ? Resolution.allow(subjectSub) : Resolution.deny();
    }

    private static boolean isAdmin(String callerGroups) {
        if (callerGroups == null || callerGroups.isBlank()) {
            return false;
        }
        return Arrays.stream(callerGroups.split(",")).map(String::trim).anyMatch(ADMIN_GROUP::equals);
    }
}
