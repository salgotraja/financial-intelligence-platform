package dev.engnotes.dsr;

import dev.engnotes.dsr.auth.SubjectResolver;
import dev.engnotes.dsr.auth.SubjectResolver.Resolution;
import dev.engnotes.dsr.model.AuditEventType;
import dev.engnotes.dsr.model.DsrRequest;
import dev.engnotes.dsr.model.DsrResponse;
import dev.engnotes.dsr.model.ErasureResult;
import dev.engnotes.dsr.model.UserDataExport;
import dev.engnotes.dsr.service.DsrAuditService;
import dev.engnotes.dsr.service.UserDataExportService;
import dev.engnotes.dsr.service.UserErasureService;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * DSR Lambda - Spring Cloud Function entry point (DPDP, spec sub-project C).
 *
 * <p>One bean {@code dsr} serves both routes; the API Gateway integration template sets {@code operation}
 * per HTTP method (GET -> EXPORT, DELETE -> ERASE), the caller {@code sub} and comma-joined groups from
 * the authorizer context, and the optional {@code subjectSub} from the {@code ?subjectSub=} query param.
 * Self-service for any group; admin-on-behalf for {@code admins}. Unauthorized calls return a denied
 * shape (200-with-error-body) with no side effects.
 */
@SpringBootApplication
public class DsrHandler {

    private static final Logger log = LoggerFactory.getLogger(DsrHandler.class);

    public static void main(String[] args) {
        SpringApplication.run(DsrHandler.class, args);
    }

    @Bean
    public Function<DsrRequest, DsrResponse> dsr(
            UserDataExportService export, UserErasureService erasure, DsrAuditService audit) {
        return request -> {
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
                    yield data;
                }
                case ERASE -> {
                    if (!resolution.allowed()) {
                        yield ErasureResult.denied();
                    }
                    // Audit first so the record survives the deletes, then erase.
                    audit.record(
                            resolution.subject(),
                            AuditEventType.ACCOUNT_ERASED,
                            request.callerSub(),
                            request.sourceIp(),
                            request.correlationId());
                    yield erasure.erase(resolution.subject());
                }
            };
        };
    }
}
