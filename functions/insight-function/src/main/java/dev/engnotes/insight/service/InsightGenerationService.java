package dev.engnotes.insight.service;

import dev.engnotes.insight.model.CorrelationGroup;
import dev.engnotes.insight.model.GroupInsightContext;
import dev.engnotes.insight.model.GroupInsightResponse;
import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.InsightResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the GenerateInsight state (Task 7): resolves the triggering ticker's correlation
 * group, and either keeps the unchanged per-ticker Bedrock insight path (no group) or generates one
 * cross-ticker insight for the whole group, subject to a 15-minute anti-spam window.
 *
 * <p>No-group path: byte-identical to the pre-Task-7 behavior - {@link BedrockInsightService#generate}
 * then {@link InsightStoreService#store}, nothing else.
 *
 * <p>Group path: at most one cross-ticker generation per group per {@code
 * MIN_GROUP_INSIGHT_INTERVAL_MINUTES} (default 15). A generation stores the canonical group insight
 * ({@link GroupInsightStoreService}) and then updates every member's existing per-ticker {@code
 * TICKER#{member}/INSIGHT#} latest via {@link InsightStoreService}, so the current UI and the
 * DynamoDB-Streams notifier keep working unchanged for every member, not only the triggering
 * ticker. A window hit is a clean success ({@code skipped=true}), not a failure - the state machine
 * must not retry or DLQ an anti-spam skip.
 */
@Service
public class InsightGenerationService {

    private static final Logger log = LoggerFactory.getLogger(InsightGenerationService.class);

    private final GroupResolutionService groupResolutionService;
    private final GroupContextReader contextReader;
    private final BedrockInsightService insightService;
    private final InsightStoreService insightStoreService;
    private final GroupInsightStoreService groupInsightStoreService;
    private final Clock clock;
    private final int minGroupInsightIntervalMinutes;

    public InsightGenerationService(
            GroupResolutionService groupResolutionService,
            GroupContextReader contextReader,
            BedrockInsightService insightService,
            InsightStoreService insightStoreService,
            GroupInsightStoreService groupInsightStoreService,
            Clock clock,
            @Value("${MIN_GROUP_INSIGHT_INTERVAL_MINUTES:15}") int minGroupInsightIntervalMinutes) {
        this.groupResolutionService = groupResolutionService;
        this.contextReader = contextReader;
        this.insightService = insightService;
        this.insightStoreService = insightStoreService;
        this.groupInsightStoreService = groupInsightStoreService;
        this.clock = clock;
        this.minGroupInsightIntervalMinutes = minGroupInsightIntervalMinutes;
    }

    public InsightResponse generate(InsightRequest request) {
        String ticker = request.getTicker();
        String correlationId = request.getCorrelationId();
        log.info("Starting insight generation. ticker={} correlationId={}", ticker, correlationId);

        Optional<CorrelationGroup> group = groupResolutionService.resolve(ticker);
        InsightResponse result =
                group.isPresent() ? generateForGroup(group.get(), request) : generatePerTicker(request);

        log.info("Insight generation complete. ticker={} correlationId={}", ticker, correlationId);
        return result;
    }

    /** Unchanged pre-Task-7 behavior for a ticker with no correlation group. */
    private InsightResponse generatePerTicker(InsightRequest request) {
        InsightResponse insight = insightService.generate(request);
        insightStoreService.store(insight);
        return insight;
    }

    private InsightResponse generateForGroup(CorrelationGroup group, InsightRequest request) {
        String groupId = group.groupId();
        String ticker = request.getTicker();
        Instant now = clock.instant();

        Optional<Instant> lastGeneratedAt = groupInsightStoreService.latestGeneratedAt(groupId);
        if (lastGeneratedAt.isPresent()
                && Duration.between(lastGeneratedAt.get(), now)
                                .compareTo(Duration.ofMinutes(minGroupInsightIntervalMinutes))
                        < 0) {
            log.info(
                    "Group insight skipped, anti-spam window active. groupId={} ticker={} lastGeneratedAt={} correlationId={}",
                    groupId,
                    ticker,
                    lastGeneratedAt.get(),
                    request.getCorrelationId());
            return InsightResponse.builder()
                    .ticker(ticker)
                    .correlationId(request.getCorrelationId())
                    .generatedAt(now.toString())
                    .skipped(true)
                    .stored(false)
                    .build();
        }

        GroupInsightContext context = contextReader.buildContext(group, request);
        GroupInsightResponse groupInsight = insightService.generateForGroup(context, request.getCorrelationId());
        groupInsightStoreService.store(groupInsight);

        InsightResponse triggeringMemberResponse = null;
        for (String member : group.members()) {
            InsightResponse memberResponse = toMemberInsightResponse(member, groupInsight);
            insightStoreService.store(memberResponse);
            if (member.equals(ticker)) {
                triggeringMemberResponse = memberResponse;
            }
        }

        if (triggeringMemberResponse == null) {
            // Defensive: the triggering ticker resolved to this group but is not in its member list -
            // a race with a concurrent correlation pass. Never seen in practice; log and return the
            // group's content for the triggering ticker without a second store call.
            log.warn(
                    "Triggering ticker not found in its own resolved group's members. ticker={} groupId={}",
                    ticker,
                    groupId);
            triggeringMemberResponse = toMemberInsightResponse(ticker, groupInsight);
        }
        return triggeringMemberResponse;
    }

    private InsightResponse toMemberInsightResponse(String member, GroupInsightResponse groupInsight) {
        return InsightResponse.builder()
                .ticker(member)
                .generatedAt(groupInsight.generatedAt())
                .signal(groupInsight.signal())
                .confidence(groupInsight.confidence())
                .rationale(groupInsight.rationale())
                .drivers(groupInsight.drivers())
                .insightText(groupInsight.rationale())
                .modelId(groupInsight.modelId())
                .promptVersion(groupInsight.promptVersion())
                .correlationId(groupInsight.correlationId())
                .source(groupInsight.source())
                .stored(false)
                .build();
    }
}
