package dev.engnotes.dsr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.dsr.model.AuditEventType;
import dev.engnotes.dsr.model.DsrOperation;
import dev.engnotes.dsr.model.DsrRequest;
import dev.engnotes.dsr.model.DsrResponse;
import dev.engnotes.dsr.model.ErasureResult;
import dev.engnotes.dsr.model.UserDataExport;
import dev.engnotes.dsr.service.DsrAuditService;
import dev.engnotes.dsr.service.UserDataExportService;
import dev.engnotes.dsr.service.UserErasureService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DsrHandlerTest {

    @Mock
    private UserDataExportService export;

    @Mock
    private UserErasureService erasure;

    @Mock
    private DsrAuditService audit;

    private DsrResponse handle(DsrRequest request) {
        return new DsrHandler().dsr(export, erasure, audit).apply(request);
    }

    @Test
    void exportReadsThenAuditsForSelfService() {
        when(export.export("user-1")).thenReturn(new UserDataExport("ok", "user-1", null, List.of(), List.of()));

        DsrResponse response =
                handle(new DsrRequest(DsrOperation.EXPORT, "user-1", "readers", null, "1.2.3.4", "corr-1"));

        InOrder order = inOrder(export, audit);
        order.verify(export).export("user-1");
        order.verify(audit).record("user-1", AuditEventType.DATA_EXPORTED, "user-1", "1.2.3.4", "corr-1");
        assertThat(((UserDataExport) response).status()).isEqualTo("ok");
    }

    @Test
    void eraseAuditsBeforeErasing() {
        when(erasure.erase("user-2")).thenReturn(new ErasureResult("erased", "user-2", 1, true));

        DsrResponse response =
                handle(new DsrRequest(DsrOperation.ERASE, "admin-1", "premium,admins", "user-2", "9.9.9.9", "corr-2"));

        InOrder order = inOrder(audit, erasure);
        order.verify(audit).record("user-2", AuditEventType.ACCOUNT_ERASED, "admin-1", "9.9.9.9", "corr-2");
        order.verify(erasure).erase("user-2");
        assertThat(((ErasureResult) response).status()).isEqualTo("erased");
    }

    @Test
    void nonAdminTargetingAnotherSubjectIsDeniedWithNoSideEffects() {
        DsrResponse response =
                handle(new DsrRequest(DsrOperation.ERASE, "user-1", "premium", "user-2", "1.1.1.1", "corr-3"));

        assertThat(((ErasureResult) response).status()).isEqualTo("denied");
        verifyNoInteractions(erasure, audit);
    }

    @Test
    void blankCallerIsDeniedForExport() {
        DsrResponse response = handle(new DsrRequest(DsrOperation.EXPORT, "", "admins", null, "1.1.1.1", "corr-4"));

        assertThat(((UserDataExport) response).status()).isEqualTo("denied");
        verify(export, never()).export(org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(audit);
    }
}
