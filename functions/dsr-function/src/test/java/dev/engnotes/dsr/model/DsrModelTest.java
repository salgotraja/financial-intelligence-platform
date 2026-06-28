package dev.engnotes.dsr.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DsrModelTest {

    @Test
    void deniedExportHasDeniedStatusAndEmptyCollections() {
        UserDataExport denied = UserDataExport.denied();

        assertThat(denied.status()).isEqualTo("denied");
        assertThat(denied.subjectSub()).isNull();
        assertThat(denied.consent()).isNull();
        assertThat(denied.watchlist()).isEmpty();
        assertThat(denied.auditTrail()).isEmpty();
    }

    @Test
    void deniedErasureHasDeniedStatusAndZeroCounts() {
        ErasureResult denied = ErasureResult.denied();

        assertThat(denied.status()).isEqualTo("denied");
        assertThat(denied.subjectSub()).isNull();
        assertThat(denied.itemsDeleted()).isZero();
        assertThat(denied.cognitoUserDeleted()).isFalse();
    }

    @Test
    void operationsAndEventTypesAreDefined() {
        assertThat(DsrOperation.valueOf("EXPORT")).isNotNull();
        assertThat(DsrOperation.valueOf("ERASE")).isNotNull();
        assertThat(AuditEventType.valueOf("DATA_EXPORTED")).isNotNull();
        assertThat(AuditEventType.valueOf("ACCOUNT_ERASED")).isNotNull();
    }
}
