package dev.engnotes.dsr.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.dsr.auth.SubjectResolver.Resolution;
import org.junit.jupiter.api.Test;

class SubjectResolverTest {

    @Test
    void selfServiceWhenNoSubjectGiven() {
        Resolution r = SubjectResolver.resolve("user-1", "readers", null);

        assertThat(r.allowed()).isTrue();
        assertThat(r.subject()).isEqualTo("user-1");
    }

    @Test
    void selfServiceWhenSubjectEqualsCaller() {
        Resolution r = SubjectResolver.resolve("user-1", "premium", "user-1");

        assertThat(r.allowed()).isTrue();
        assertThat(r.subject()).isEqualTo("user-1");
    }

    @Test
    void adminMayTargetAnotherSubject() {
        Resolution r = SubjectResolver.resolve("admin-1", "premium,admins", "user-2");

        assertThat(r.allowed()).isTrue();
        assertThat(r.subject()).isEqualTo("user-2");
    }

    @Test
    void nonAdminTargetingAnotherSubjectIsDenied() {
        Resolution r = SubjectResolver.resolve("user-1", "premium", "user-2");

        assertThat(r.allowed()).isFalse();
        assertThat(r.subject()).isNull();
    }

    @Test
    void blankCallerIsDenied() {
        assertThat(SubjectResolver.resolve("", "admins", null).allowed()).isFalse();
        assertThat(SubjectResolver.resolve(null, "admins", null).allowed()).isFalse();
    }

    @Test
    void blankSubjectTreatedAsSelf() {
        Resolution r = SubjectResolver.resolve("user-1", "readers", "");

        assertThat(r.allowed()).isTrue();
        assertThat(r.subject()).isEqualTo("user-1");
    }
}
