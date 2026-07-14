package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.dsr.model.ErasureAcceptance;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ErasureWorkflowServiceTest {

    private static final String STATE_MACHINE_ARN = "arn:aws:states:ap-south-1:123:stateMachine:financial-erasure-dev";
    private static final Instant NOW = Instant.parse("2026-07-14T09:30:00Z");

    @Mock
    private SfnClient sfn;

    @Mock
    private UserErasureService erasure;

    private ErasureWorkflowService workflow;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        workflow = new ErasureWorkflowService(sfn, erasure, new ObjectMapper(), STATE_MACHINE_ARN, clock);
    }

    @Test
    void startErasureIsNoOpWhenAlreadyPending() {
        when(erasure.isDeletionPending("user-1")).thenReturn(true);

        ErasureAcceptance result = workflow.startErasure("user-1", "user-1", "1.2.3.4", "corr-1");

        assertThat(result.status()).isEqualTo("accepted");
        assertThat(result.subjectSub()).isEqualTo("user-1");
        assertThat(result.executionArn()).isNull();
        verify(sfn, never()).startExecution(any(StartExecutionRequest.class));
    }

    @Test
    void startErasureStartsExecutionWithFullContextWhenNotPending() {
        when(erasure.isDeletionPending("user-2")).thenReturn(false);
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder()
                        .executionArn("arn:aws:states:...:execution:financial-erasure-dev:abc")
                        .build());

        ErasureAcceptance result = workflow.startErasure("user-2", "admin-1", "9.9.9.9", "corr-2");

        assertThat(result.status()).isEqualTo("accepted");
        assertThat(result.executionArn()).isEqualTo("arn:aws:states:...:execution:financial-erasure-dev:abc");

        ArgumentCaptor<StartExecutionRequest> captor = ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfn).startExecution(captor.capture());
        StartExecutionRequest request = captor.getValue();
        assertThat(request.stateMachineArn()).isEqualTo(STATE_MACHINE_ARN);
        assertThat(request.input())
                .contains("\"subjectSub\":\"user-2\"")
                .contains("\"callerSub\":\"admin-1\"")
                .contains("\"sourceIp\":\"9.9.9.9\"")
                .contains("\"correlationId\":\"corr-2\"")
                .contains("\"requestedAt\":\"" + NOW + "\"");
    }
}
