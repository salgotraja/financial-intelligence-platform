package dev.engnotes.insight.service;

import dev.engnotes.insight.exception.InsightException;
import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.InsightResponse;
import dev.engnotes.insight.model.Signal;
import dev.engnotes.insight.model.StructuredInsight;
import dev.engnotes.insight.service.prompt.FinancialInsightPrompt;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generates a per-ticker insight via the Bedrock Messages API with a strict structured-output
 * contract (spec section 9).
 *
 * <p>Structured output: the request forces a single {@code emit_insight} tool whose input schema
 * constrains the model to {signal, confidence, rationale, drivers}. The model supplies only the
 * judgment; the service stamps identity and time, so the LLM never invents data. The tool input is
 * parsed and validated by the {@link StructuredInsight} record; invalid output (missing field, bad
 * signal, out-of-range confidence) is rejected and retried once.
 *
 * <p>Always-usable insight: on Bedrock throttling, model timeout, any other call failure, or
 * repeatedly invalid output, the service falls back to a deterministic {@link RuleBasedInsightGenerator}
 * insight built from the same data, in the same schema. So unlike the previous free-text version, a
 * throttle no longer fails the state into the DLQ; the platform always returns an insight, tagged
 * with its {@code source} (BEDROCK or RULE_BASED). The cost circuit breaker (spec section 9), via
 * {@link CostTrackingService}, routes to the same fallback when the daily Bedrock spend cap is hit.
 *
 * <p>Model: Sonnet 4.5 is INFERENCE_PROFILE-only in ap-south-1, so BEDROCK_MODEL_ID is the global
 * inference-profile id, not the bare foundation-model id (set in IngestionStack).
 */
@Service
public class BedrockInsightService {

    private static final Logger log = LoggerFactory.getLogger(BedrockInsightService.class);

    private static final String ANTHROPIC_VERSION = "bedrock-2023-05-31";
    private static final String TOOL_NAME = "emit_insight";
    private static final int MAX_ATTEMPTS = 2;
    private static final String SOURCE_BEDROCK = "BEDROCK";
    private static final String SOURCE_RULE_BASED = "RULE_BASED";

    private final BedrockRuntimeClient bedrock;
    private final ObjectMapper objectMapper;
    private final FinancialInsightPrompt prompt;
    private final RuleBasedInsightGenerator fallback;
    private final CostTrackingService costTracker;

    @Value("${BEDROCK_MODEL_ID:global.anthropic.claude-sonnet-4-6}")
    private String modelId;

    @Value("${BEDROCK_MAX_TOKENS:1024}")
    private int maxTokens;

    public BedrockInsightService(
            BedrockRuntimeClient bedrock,
            ObjectMapper objectMapper,
            FinancialInsightPrompt prompt,
            RuleBasedInsightGenerator fallback,
            CostTrackingService costTracker) {
        this.bedrock = bedrock;
        this.objectMapper = objectMapper;
        this.prompt = prompt;
        this.fallback = fallback;
        this.costTracker = costTracker;
    }

    public InsightResponse generate(InsightRequest data) {
        String ticker = data.getTicker();
        if (ticker == null || ticker.isBlank()) {
            throw new InsightException("Insight request is missing a ticker");
        }

        long startMs = System.currentTimeMillis();
        // Cost circuit breaker (spec section 9): once the day's Bedrock spend reaches the cap, skip
        // the model entirely and serve the deterministic fallback until the UTC date rolls over.
        boolean breakerOpen = costTracker.isBreakerOpen();
        if (breakerOpen) {
            log.warn(
                    "Cost circuit breaker open, skipping Bedrock for rule-based fallback. ticker={} correlationId={}",
                    ticker,
                    data.getCorrelationId());
        }
        Optional<StructuredInsight> bedrockResult = breakerOpen ? Optional.empty() : tryBedrock(data);
        boolean fromBedrock = bedrockResult.isPresent();
        StructuredInsight insight = bedrockResult.orElseGet(() -> fallback.generate(data));
        String source = fromBedrock ? SOURCE_BEDROCK : SOURCE_RULE_BASED;

        log.info(
                "Insight ready. ticker={} source={} signal={} confidence={} latencyMs={} correlationId={}",
                ticker,
                source,
                insight.signal(),
                insight.confidence(),
                System.currentTimeMillis() - startMs,
                data.getCorrelationId());

        return toResponse(data, insight, source);
    }

    /** Calls Bedrock, retrying once on invalid output; empty triggers the rule-based fallback. */
    private Optional<StructuredInsight> tryBedrock(InsightRequest data) {
        String requestBody = buildToolRequest(prompt.promptText(data));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                InvokeModelResponse response = bedrock.invokeModel(InvokeModelRequest.builder()
                        .modelId(modelId)
                        .contentType("application/json")
                        .accept("application/json")
                        .body(SdkBytes.fromUtf8String(requestBody))
                        .build());

                // Tokens are billed whether or not the output parses, so record before validating.
                String responseBody = response.body().asUtf8String();
                recordCost(data, responseBody);

                StructuredInsight insight = parseToolUse(responseBody);
                return Optional.of(insight);

            } catch (ThrottlingException | ModelTimeoutException e) {
                log.warn(
                        "Bedrock unavailable, using rule-based fallback. ticker={} error={}",
                        data.getTicker(),
                        e.getClass().getSimpleName());
                return Optional.empty();
            } catch (IllegalArgumentException e) {
                log.warn(
                        "Invalid Bedrock output. ticker={} attempt={}/{} reason={}",
                        data.getTicker(),
                        attempt,
                        MAX_ATTEMPTS,
                        e.getMessage());
                // fall through to retry
            } catch (Exception e) {
                log.warn(
                        "Bedrock call failed, using rule-based fallback. ticker={} error={}",
                        data.getTicker(),
                        e.toString());
                return Optional.empty();
            }
        }

        log.warn(
                "Bedrock returned invalid output {} times, using rule-based fallback. ticker={}",
                MAX_ATTEMPTS,
                data.getTicker());
        return Optional.empty();
    }

    private String buildToolRequest(String promptText) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("anthropic_version", ANTHROPIC_VERSION);
        body.put("max_tokens", maxTokens);

        ObjectNode signalProp = objectMapper.createObjectNode().put("type", "string");
        ArrayNode signalEnum = signalProp.putArray("enum");
        for (Signal s : Signal.values()) {
            signalEnum.add(s.name());
        }
        ObjectNode confidenceProp = objectMapper
                .createObjectNode()
                .put("type", "number")
                .put("minimum", 0)
                .put("maximum", 1);
        ObjectNode driversProp = objectMapper.createObjectNode().put("type", "array");
        driversProp.set("items", objectMapper.createObjectNode().put("type", "string"));

        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("signal", signalProp);
        properties.set("confidence", confidenceProp);
        properties.set("rationale", objectMapper.createObjectNode().put("type", "string"));
        properties.set("drivers", driversProp);

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        ArrayNode required = schema.putArray("required");
        required.add("signal");
        required.add("confidence");
        required.add("rationale");
        required.add("drivers");

        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Return the structured financial insight for the ticker.");
        tool.set("input_schema", schema);
        body.putArray("tools").add(tool);

        ObjectNode toolChoice = objectMapper.createObjectNode();
        toolChoice.put("type", "tool");
        toolChoice.put("name", TOOL_NAME);
        body.set("tool_choice", toolChoice);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", promptText);
        body.putArray("messages").add(message);

        return objectMapper.writeValueAsString(body);
    }

    /** Reads token usage from the Bedrock response and records the invocation's cost. */
    private void recordCost(InsightRequest data, String responseJson) {
        long inputTokens = 0;
        long outputTokens = 0;
        try {
            JsonNode usage = objectMapper.readTree(responseJson).path("usage");
            inputTokens = usage.path("input_tokens").asLong(0);
            outputTokens = usage.path("output_tokens").asLong(0);
        } catch (Exception e) {
            log.warn("Could not read Bedrock token usage, recording zero. ticker={}", data.getTicker());
        }
        costTracker.record(data.getCorrelationId(), inputTokens, outputTokens);
    }

    /** Extracts and validates the forced tool_use block. Throws IllegalArgumentException if invalid. */
    private StructuredInsight parseToolUse(String responseJson) {
        JsonNode content = objectMapper.readTree(responseJson).path("content");
        if (!content.isArray()) {
            throw new IllegalArgumentException("response has no content array");
        }
        for (JsonNode block : content) {
            if ("tool_use".equals(block.path("type").asString(""))) {
                return mapInsight(block.path("input"));
            }
        }
        throw new IllegalArgumentException("no tool_use block in response");
    }

    private StructuredInsight mapInsight(JsonNode input) {
        if (!input.has("signal") || !input.path("confidence").isNumber() || !input.has("rationale")) {
            throw new IllegalArgumentException("missing or malformed insight fields");
        }
        Signal signal = Signal.parse(input.path("signal").asString(""));
        double confidence = input.path("confidence").asDouble();
        String rationale = input.path("rationale").asString("");

        List<String> drivers = new ArrayList<>();
        JsonNode driversNode = input.path("drivers");
        if (driversNode.isArray()) {
            for (JsonNode driver : driversNode) {
                drivers.add(driver.asString(""));
            }
        }
        return new StructuredInsight(signal, confidence, rationale, drivers);
    }

    private InsightResponse toResponse(InsightRequest data, StructuredInsight insight, String source) {
        return InsightResponse.builder()
                .ticker(data.getTicker())
                .generatedAt(Instant.now().toString())
                .signal(insight.signal().name())
                .confidence(insight.confidence())
                .rationale(insight.rationale())
                .drivers(insight.drivers())
                .insightText(insight.rationale()) // mirror for the read path
                .modelId(modelId)
                .promptVersion(prompt.version())
                .correlationId(data.getCorrelationId())
                .source(source)
                .stored(false)
                .build();
    }
}
