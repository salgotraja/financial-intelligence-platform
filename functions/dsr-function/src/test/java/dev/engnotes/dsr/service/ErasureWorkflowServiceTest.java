package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.dsr.model.ErasureAcceptance;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
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
        // Deterministic and injective: "erasure-" + SHA-256 hex of "user-2#2026-07-14T09:30:00Z".
        // Expected literal computed independently via `printf '...' | shasum -a 256`.
        assertThat(request.name())
                .isEqualTo("erasure-4c9322d6eeda64175234e4f0ba30f9c0aca9950dc9b300bc61ad33b455c40f6e");
    }

    @Test
    void startErasureIsIdempotentWhenExecutionAlreadyExists() {
        when(erasure.isDeletionPending("user-3")).thenReturn(false);
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(ExecutionAlreadyExistsException.builder()
                        .message("Execution already exists")
                        .build());

        ErasureAcceptance result = workflow.startErasure("user-3", "user-3", "1.2.3.4", "corr-3");

        assertThat(result.status()).isEqualTo("accepted");
        assertThat(result.subjectSub()).isEqualTo("user-3");
        assertThat(result.executionArn()).isNull();
    }

    // Injectivity against hostile subjectSub values: these four subjects would all collapse to
    // "usera" under charset-stripping, silently no-oping a legitimate erasure via
    // ExecutionAlreadyExists. Hash-based names must stay distinct, valid, and within 80 chars.
    @Test
    void startErasureExecutionNamesStayDistinctAndValidForHostileSubjects() {
        List<String> hostileSubjects = List.of("user:a", "user/a", "useréa", "usera", "a".repeat(100));
        when(erasure.isDeletionPending(anyString())).thenReturn(false);
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder()
                        .executionArn("arn:aws:states:...:execution:financial-erasure-dev:xyz")
                        .build());

        for (String subject : hostileSubjects) {
            workflow.startErasure(subject, subject, "1.2.3.4", "corr-4");
        }

        ArgumentCaptor<StartExecutionRequest> captor = ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfn, times(hostileSubjects.size())).startExecution(captor.capture());
        List<String> names =
                captor.getAllValues().stream().map(StartExecutionRequest::name).toList();
        assertThat(names).doesNotHaveDuplicates();
        for (String name : names) {
            assertThat(name).hasSizeLessThanOrEqualTo(80);
            assertThat(name).matches("[A-Za-z0-9_-]+");
        }
    }
}
