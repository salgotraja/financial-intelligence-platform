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
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts the {@code financial-erasure} Step Functions workflow (spec s11, Task 11), backing
 * {@code DELETE /user/account}. Idempotent: a subject already deletion-pending gets a no-op accepted
 * response, no second execution. This is the API-side handler op referred to in the task's design
 * decisions; {@link UserErasureService#isDeletionPending} is the source of truth for the pending check
 * (the same PROFILE item {@code MarkDeletionPending} writes and {@code WriteErasureAudit} clears).
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

        Map<String, String> input = new LinkedHashMap<>();
        input.put("subjectSub", subjectSub);
        input.put("callerSub", callerSub);
        input.put("sourceIp", sourceIp);
        input.put("correlationId", correlationId);
        input.put("requestedAt", Instant.now(clock).toString());

        StartExecutionResponse response = sfn.startExecution(StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .input(objectMapper.writeValueAsString(input))
                .build());
        log.info("Started erasure workflow. subjectSub={} executionArn={}", subjectSub, response.executionArn());
        return ErasureAcceptance.started(subjectSub, response.executionArn());
    }
}
