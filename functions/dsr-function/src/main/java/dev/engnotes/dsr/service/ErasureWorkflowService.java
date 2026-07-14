package dev.engnotes.dsr.service;

import dev.engnotes.dsr.model.ErasureAcceptance;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts the {@code financial-erasure} Step Functions workflow (spec s11, Task 11), backing
 * {@code DELETE /user/account}. Idempotent two ways: a subject already deletion-pending (per
 * {@link UserErasureService#isDeletionPending}, the same PROFILE item {@code MarkDeletionPending}
 * writes and {@code WriteErasureAudit} clears) gets a no-op accepted response with no second
 * execution; and the Step Functions execution name is derived deterministically from the subject and
 * request timestamp (Task 12), so a concurrent duplicate request that races past the pending check
 * hits {@link ExecutionAlreadyExistsException} instead of starting a second execution - caught here
 * and folded into the same idempotent 202 accepted response.
 */
@Service
public class ErasureWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(ErasureWorkflowService.class);

    private final SfnClient sfn;
    private final UserErasureService erasure;
    private final ObjectMapper objectMapper;
    private final String stateMachineArn;
    private final Clock clock;

    public ErasureWorkflowService(
            SfnClient sfn,
            UserErasureService erasure,
            ObjectMapper objectMapper,
            @Value("${STATE_MACHINE_ARN:}") String stateMachineArn,
            Clock clock) {
        this.sfn = sfn;
        this.erasure = erasure;
        this.objectMapper = objectMapper;
        this.stateMachineArn = stateMachineArn;
        this.clock = clock;
    }

    public ErasureAcceptance startErasure(String subjectSub, String callerSub, String sourceIp, String correlationId) {
        if (erasure.isDeletionPending(subjectSub)) {
            log.info("Erasure already pending; no-op start. subjectSub={}", subjectSub);
            return ErasureAcceptance.alreadyPending(subjectSub);
        }

        String requestedAt = Instant.now(clock).toString();
        Map<String, String> input = new LinkedHashMap<>();
        input.put("subjectSub", subjectSub);
        input.put("callerSub", callerSub);
        input.put("sourceIp", sourceIp);
        input.put("correlationId", correlationId);
        input.put("requestedAt", requestedAt);

        String executionName = executionName(subjectSub, requestedAt);
        try {
            StartExecutionResponse response = sfn.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(stateMachineArn)
                    .name(executionName)
                    .input(objectMapper.writeValueAsString(input))
                    .build());
            log.info("Started erasure workflow. subjectSub={} executionArn={}", subjectSub, response.executionArn());
            return ErasureAcceptance.started(subjectSub, response.executionArn());
        } catch (ExecutionAlreadyExistsException e) {
            // A concurrent duplicate request raced past the isDeletionPending check above and landed on
            // the same deterministic execution name; the other request's execution is already running,
            // so this is an idempotent accept, not a failure.
            log.info(
                    "Erasure execution already exists for deterministic name; idempotent accept."
                            + " subjectSub={} executionName={}",
                    subjectSub,
                    executionName);
            return ErasureAcceptance.alreadyPending(subjectSub);
        }
    }

    /**
     * Deterministic Step Functions execution name (Task 12): {@code erasure-} plus the SHA-256 hex of
     * {@code subjectSub + "#" + requestedAt}. Hashing (rather than sanitizing the raw values) keeps the
     * name injective per (subject, requestedAt) pair: character-stripping or truncation of an
     * admin-supplied {@code subjectSub} could make two distinct subjects collide onto one name, and a
     * collision here silently no-ops a legitimate erasure as {@code ExecutionAlreadyExists}. Hex is
     * entirely within the execution-name charset and the result is a fixed 72 characters, under the
     * 80-character limit.
     */
    private static String executionName(String subjectSub, String requestedAt) {
        return "erasure-" + Hashing.sha256Hex(subjectSub + "#" + requestedAt);
    }
}
