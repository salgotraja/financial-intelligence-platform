package dev.engnotes.insight.service;

import dev.engnotes.insight.exception.InsightException;
import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.InsightResponse;
import dev.engnotes.insight.service.prompt.FinancialInsightPrompt;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generates a per-ticker insight via the Bedrock Messages API.
 *
 * Model: Sonnet 4.5 is INFERENCE_PROFILE-only in ap-south-1, so BEDROCK_MODEL_ID is the
 * global inference-profile id, not the bare foundation-model id (set in IngestionStack).
 *
 * Failure handling: throttling and model-timeout are surfaced as InsightException so the
 * Step Functions standardRetry retries with backoff (the SDK retries are disabled in
 * BedrockConfig to avoid amplification). A malformed or empty model response is also an
 * InsightException rather than a silent empty insight, so a bad prompt fails loudly into
 * the DLQ instead of persisting garbage. max_tokens caps the spend per call.
 */
@Service
public class BedrockInsightService {

    private static final Logger log = LoggerFactory.getLogger(BedrockInsightService.class);

    private static final String ANTHROPIC_VERSION = "bedrock-2023-05-31";

    private final BedrockRuntimeClient bedrock;
    private final ObjectMapper objectMapper;
    private final FinancialInsightPrompt prompt;

    @Value("${BEDROCK_MODEL_ID:global.anthropic.claude-sonnet-4-5-20250929-v1:0}")
    private String modelId;

    @Value("${BEDROCK_MAX_TOKENS:1024}")
    private int maxTokens;

    public BedrockInsightService(
            BedrockRuntimeClient bedrock, ObjectMapper objectMapper, FinancialInsightPrompt prompt) {
        this.bedrock = bedrock;
        this.objectMapper = objectMapper;
        this.prompt = prompt;
    }

    public InsightResponse generate(InsightRequest data) {
        String ticker = data.getTicker();
        String correlationId = data.getCorrelationId();
        long startMs = System.currentTimeMillis();

        if (ticker == null || ticker.isBlank()) {
            throw new InsightException("Insight request is missing a ticker");
        }

        String requestBody = buildRequestBody(prompt.promptText(data));

        try {
            InvokeModelResponse response = bedrock.invokeModel(InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(requestBody))
                    .build());

            String insightText = parseInsightText(response.body().asUtf8String(), ticker);

            log.info(
                    "Insight generated. ticker={} latencyMs={} correlationId={}",
                    ticker,
                    System.currentTimeMillis() - startMs,
                    correlationId);

            return InsightResponse.builder()
                    .ticker(ticker)
                    .generatedAt(Instant.now().toString())
                    .insightText(insightText)
                    .modelId(modelId)
                    .promptVersion(prompt.version())
                    .correlationId(correlationId)
                    .stored(false)
                    .build();

        } catch (ThrottlingException e) {
            throw new InsightException("Bedrock throttled the insight request for ticker " + ticker, e);
        } catch (ModelTimeoutException e) {
            throw new InsightException("Bedrock timed out generating an insight for ticker " + ticker, e);
        } catch (InsightException e) {
            throw e;
        } catch (Exception e) {
            throw new InsightException("Failed to generate insight for ticker " + ticker, e);
        }
    }

    private String buildRequestBody(String promptText) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("anthropic_version", ANTHROPIC_VERSION);
        body.put("max_tokens", maxTokens);
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", promptText);
        body.putArray("messages").add(message);
        return objectMapper.writeValueAsString(body);
    }

    private String parseInsightText(String responseJson, String ticker) {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new InsightException("Bedrock returned no content for ticker " + ticker);
        }
        String text = content.get(0).path("text").asString("");
        if (text.isBlank()) {
            throw new InsightException("Bedrock returned empty insight text for ticker " + ticker);
        }
        return text.strip();
    }
}
