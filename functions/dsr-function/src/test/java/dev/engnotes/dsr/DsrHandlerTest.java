package dev.engnotes.dsr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.dsr.model.AuditEventType;
import dev.engnotes.dsr.model.ComplianceEventType;
import dev.engnotes.dsr.model.DsrOperation;
import dev.engnotes.dsr.model.DsrRequest;
import dev.engnotes.dsr.model.DsrResponse;
import dev.engnotes.dsr.model.ErasureResult;
import dev.engnotes.dsr.model.UserDataExport;
import dev.engnotes.dsr.service.DsrAuditService;
import dev.engnotes.dsr.service.ErasureService;
import dev.engnotes.dsr.service.UserDataExportService;
import dev.engnotes.observability.Metrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DsrHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-14T10:00:00Z");

    @Mock
    private UserDataExportService export;

    @Mock
    private DsrAuditService audit;

    @Mock
    private ErasureService erasureService;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Metrics.Capture capture = Metrics.forTesting();

    private DsrResponse handle(DsrRequest request) {
        return new DsrHandler()
                .dsr(export, audit, erasureService, clock, capture.metrics())
                .apply(request);
    }

    @Test
    void exportReadsThenAuditsForSelfService() {
        when(export.export("user-1"))
                .thenReturn(new UserDataExport("ok", "user-1", null, List.of(), List.of(), List.of()));

        DsrResponse response =
                handle(new DsrRequest(DsrOperation.EXPORT, "user-1", "readers", null, "1.2.3.4", "corr-1"));

        InOrder order = inOrder(export, audit);
        order.verify(export).export("user-1");
        order.verify(audit).record("user-1", AuditEventType.DATA_EXPORTED, "user-1", "1.2.3.4", "corr-1");
        order.verify(audit)
                .recordCompliance(ComplianceEventType.ACCESS, "user-1", "user-1", NOW.toString(), "corr-1", null);
        assertThat(((UserDataExport) response).status()).isEqualTo("ok");
        assertThat(capture.records()).anySatisfy(record -> assertThat(record).contains("\"ExportGenerated\""));
    }

    @Test
    void nonAdminTargetingAnotherSubjectIsDeniedWithNoSideEffects() {
        DsrResponse response =
                handle(new DsrRequest(DsrOperation.ERASE, "user-1", "premium", "user-2", "1.1.1.1", "corr-3"));

        assertThat(((ErasureResult) response).status()).isEqualTo("denied");
        verifyNoInteractions(erasureService, audit);
    }

    @Test
    void blankCallerIsDeniedForExport() {
        DsrResponse response = handle(new DsrRequest(DsrOperation.EXPORT, "", "admins", null, "1.1.1.1", "corr-4"));

        assertThat(((UserDataExport) response).status()).isEqualTo("denied");
        verify(export, never()).export(anyString());
        verifyNoInteractions(audit);
        assertThat(capture.records()).noneSatisfy(record -> assertThat(record).contains("\"ExportGenerated\""));
    }

    @Test
    void eraseRunsCascadeForSelfService() {
        ErasureResult completed =
                new ErasureResult("erased", "sub-1", 5, true, true, "2026-07-19T10:00:00Z", "2026-07-19T10:00:01Z");
        when(erasureService.erase("sub-1", "sub-1", "1.2.3.4", "corr-1")).thenReturn(completed);

        DsrResponse response = handle(new DsrRequest(DsrOperation.ERASE, "sub-1", "users", null, "1.2.3.4", "corr-1"));

        assertEquals(completed, response);
    }

    @Test
    void eraseAdminOnBehalfTargetsSubjectSub() {
        ErasureResult completed =
                new ErasureResult("erased", "sub-2", 3, true, false, "2026-07-19T10:00:00Z", "2026-07-19T10:00:01Z");
        when(erasureService.erase("sub-2", "admin-1", "1.2.3.4", "corr-1")).thenReturn(completed);

        DsrResponse response =
                handle(new DsrRequest(DsrOperation.ERASE, "admin-1", "admins", "sub-2", "1.2.3.4", "corr-1"));

        assertEquals(completed, response);
    }
}
