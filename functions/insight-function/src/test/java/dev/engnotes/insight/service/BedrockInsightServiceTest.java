package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.exception.InsightException;
import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.InsightResponse;
import dev.engnotes.insight.service.prompt.FinancialInsightPrompt;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class BedrockInsightServiceTest {

    private static final String VALID = toolUse("BULLISH", "0.82", "Strong move on above-average volume.");
    private static final String INVALID_CONFIDENCE = toolUse("BULLISH", "2.5", "Confidence is out of range.");
    private static final String TEXT_ONLY = "{\"content\":[{\"type\":\"text\",\"text\":\"no tool here\"}]}";

    @Mock
    private BedrockRuntimeClient bedrock;

    @Mock
    private CostTrackingService costTracker;

    private BedrockInsightService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        service = new BedrockInsightService(
                bedrock,
                objectMapper,
                new FinancialInsightPrompt(),
                new RuleBasedInsightGenerator(1.0, 0.4),
                costTracker);
        ReflectionTestUtils.setField(service, "modelId", "test-model");
        ReflectionTestUtils.setField(service, "maxTokens", 512);
    }

    @Test
    void validToolUseResponseProducesBedrockInsight() {
        when(bedrock.invokeModel(any(InvokeModelRequest.class))).thenReturn(modelResponse(VALID));

        InsightResponse response = service.generate(request("RELIANCE.NS", "3.2"));

        assertThat(response.getSource()).isEqualTo("BEDROCK");
        assertThat(response.getSignal()).isEqualTo("BULLISH");
        assertThat(response.getConfidence()).isEqualTo(0.82);
        assertThat(response.getRationale()).isEqualTo("Strong move on above-average volume.");
        assertThat(response.getInsightText()).isEqualTo("Strong move on above-average volume.");
        assertThat(response.getModelId()).isEqualTo("test-model");
        assertThat(response.getPromptVersion()).isEqualTo("v2");
        verify(bedrock, times(1)).invokeModel(any(InvokeModelRequest.class));
    }

    @Test
    void invalidOutputIsRetriedOnceThenSucceeds() {
        when(bedrock.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(modelResponse(INVALID_CONFIDENCE))
                .thenReturn(modelResponse(VALID));

        InsightResponse response = service.generate(request("RELIANCE.NS", "3.2"));

        assertThat(response.getSource()).isEqualTo("BEDROCK");
        assertThat(response.getSignal()).isEqualTo("BULLISH");
        verify(bedrock, times(2)).invokeModel(any(InvokeModelRequest.class));
    }

    @Test
    void repeatedInvalidOutputFallsBackToRuleBased() {
        when(bedrock.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(modelResponse(INVALID_CONFIDENCE))
                .thenReturn(modelResponse(INVALID_CONFIDENCE));

        InsightResponse response = service.generate(request("RELIANCE.NS", "5.0"));

        assertThat(response.getSource()).isEqualTo("RULE_BASED");
        assertThat(response.getSignal()).isEqualTo("BULLISH"); // derived from +5% change
        verify(bedrock, times(2)).invokeModel(any(InvokeModelRequest.class));
    }

    @Test
    void responseWithoutToolUseBlockFallsBackAfterRetry() {
        when(bedrock.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(modelResponse(TEXT_ONLY))
                .thenReturn(modelResponse(TEXT_ONLY));

        InsightResponse response = service.generate(request("TCS.NS", "-4.0"));

        assertThat(response.getSource()).isEqualTo("RULE_BASED");
        assertThat(response.getSignal()).isEqualTo("BEARISH");
        verify(bedrock, times(2)).invokeModel(any(InvokeModelRequest.class));
    }

    @Test
    void throttleFallsBackImmediatelyWithoutRetry() {
        when(bedrock.invokeModel(any(InvokeModelRequest.class)))
                .thenThrow(ThrottlingException.builder().message("slow down").build());

        InsightResponse response = service.generate(request("INFY.NS", "0.1"));

        assertThat(response.getSource()).isEqualTo("RULE_BASED");
        assertThat(response.getSignal()).isEqualTo("NEUTRAL");
        verify(bedrock, times(1)).invokeModel(any(InvokeModelRequest.class));
    }

    @Test
    void openCircuitBreakerSkipsBedrockAndServesRuleBasedFallback() {
        when(costTracker.isBreakerOpen()).thenReturn(true);

        InsightResponse response = service.generate(request("RELIANCE.NS", "5.0"));

        assertThat(response.getSource()).isEqualTo("RULE_BASED");
        assertThat(response.getSignal()).isEqualTo("BULLISH"); // derived from +5% change
        verify(bedrock, never()).invokeModel(any(InvokeModelRequest.class));
        verify(costTracker, never()).record(any(), anyLong(), anyLong());
    }

    @Test
    void successfulBedrockCallRecordsTokenCost() {
        when(bedrock.invokeModel(any(InvokeModelRequest.class))).thenReturn(modelResponse(VALID));

        service.generate(request("RELIANCE.NS", "3.2"));

        verify(costTracker).record(eq("corr-1"), eq(1200L), eq(300L));
    }

    @Test
    void missingTickerThrows() {
        assertThatThrownBy(() -> service.generate(new InsightRequest())).isInstanceOf(InsightException.class);
    }

    private static InvokeModelResponse modelResponse(String json) {
        return InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(json)).build();
    }

    private static InsightRequest request(String ticker, String changePercent) {
        InsightRequest data = new InsightRequest();
        data.setTicker(ticker);
        data.setCorrelationId("corr-1");
        data.setChangePercent(new BigDecimal(changePercent));
        data.setVolume(1000L);
        return data;
    }

    private static String toolUse(String signal, String confidence, String rationale) {
        return "{\"content\":[{\"type\":\"tool_use\",\"name\":\"emit_insight\",\"input\":{"
                + "\"signal\":\"" + signal + "\","
                + "\"confidence\":" + confidence + ","
                + "\"rationale\":\"" + rationale + "\","
                + "\"drivers\":[\"change percent\",\"volume\"]}}],"
                + "\"usage\":{\"input_tokens\":1200,\"output_tokens\":300}}";
    }
}
