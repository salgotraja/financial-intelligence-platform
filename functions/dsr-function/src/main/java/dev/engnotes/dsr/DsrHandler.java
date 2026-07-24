package dev.engnotes.dsr;

import dev.engnotes.dsr.auth.SubjectResolver;
import dev.engnotes.dsr.auth.SubjectResolver.Resolution;
import dev.engnotes.dsr.model.AuditEventType;
import dev.engnotes.dsr.model.ComplianceEventType;
import dev.engnotes.dsr.model.DsrRequest;
import dev.engnotes.dsr.model.DsrResponse;
import dev.engnotes.dsr.model.ErasureResult;
import dev.engnotes.dsr.model.UserDataExport;
import dev.engnotes.dsr.service.DsrAuditService;
import dev.engnotes.dsr.service.ErasureService;
import dev.engnotes.dsr.service.UserDataExportService;
import dev.engnotes.observability.Metrics;
import dev.engnotes.observability.RequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * DSR Lambda - Spring Cloud Function entry point (DPDP, spec sub-project C).
 *
 * <p>One bean {@code dsr} serves both HTTP routes. The API Gateway integration template sets
 * {@code operation} per HTTP method (GET -> EXPORT, DELETE -> ERASE), the caller {@code sub} and
 * comma-joined groups from the authorizer context, and the optional {@code subjectSub} from the
 * {@code ?subjectSub=} query param. Self-service for any group; admin-on-behalf for {@code admins}.
 * Unauthorized calls return a denied shape with no side effects. ERASE runs the erasure cascade
 * synchronously (the {@code financial-erasure} Step Functions workflow was collapsed 2026-07-19; no
 * state-machine callers remain).
 */
@SpringBootApplication
public class DsrHandler {

    private static final Logger log = LoggerFactory.getLogger(DsrHandler.class);

    private static final String MODULE_LABEL = "financial-dsr";

    public static void main(String[] args) {
        SpringApplication.run(DsrHandler.class, args);
    }

    @Bean
    public Metrics metrics() {
        return Metrics.forFunction(MODULE_LABEL);
    }

    @Bean
    public Function<DsrRequest, DsrResponse> dsr(
            UserDataExportService export,
            DsrAuditService audit,
            ErasureService erasureService,
            Clock clock,
            Metrics metrics) {
        return request -> {
            try (var ctx = RequestContext.begin(MODULE_LABEL, request.correlationId())) {
                ctx.withUser(request.subjectSub());
                Resolution resolution =
                        SubjectResolver.resolve(request.callerSub(), request.callerGroups(), request.subjectSub());
                log.info(
                        "DSR request. operation={} callerSub={} subjectSub={} allowed={} correlationId={}",
                        request.operation(),
                        request.callerSub(),
                        request.subjectSub(),
                        resolution.allowed(),
                        request.correlationId());

                return switch (request.operation()) {
                    case EXPORT -> {
                        if (!resolution.allowed()) {
                            yield UserDataExport.denied();
                        }
                        UserDataExport data = export.export(resolution.subject());
                        audit.record(
                                resolution.subject(),
                                AuditEventType.DATA_EXPORTED,
                                request.callerSub(),
                                request.sourceIp(),
                                request.correlationId());
                        audit.recordCompliance(
                                ComplianceEventType.ACCESS,
                                resolution.subject(),
                                request.callerSub(),
                                Instant.now(clock).toString(),
                                request.correlationId(),
                                null);
                        metrics.count("ExportGenerated");
                        yield data;
                    }

                    // Synchronous erasure cascade (Step Functions workflow collapsed 2026-07-19):
                    // idempotent via the conditional deletion lease in ErasureService.
                    case ERASE -> {
                        if (!resolution.allowed()) {
                            yield ErasureResult.denied();
                        }
                        yield erasureService.erase(
                                resolution.subject(), request.callerSub(), request.sourceIp(), request.correlationId());
                    }
                };
            }
        };
    }
}
