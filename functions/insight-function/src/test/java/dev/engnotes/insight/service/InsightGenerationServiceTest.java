package dev.engnotes.insight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.engnotes.insight.model.CorrelationGroup;
import dev.engnotes.insight.model.GroupInsightContext;
import dev.engnotes.insight.model.GroupInsightResponse;
import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.InsightResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InsightGenerationServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-14T10:30:00Z"), ZoneOffset.UTC);
    private static final int INTERVAL_MINUTES = 15;

    @Mock
    private GroupResolutionService groupResolutionService;

    @Mock
    private GroupContextReader contextReader;

    @Mock
    private BedrockInsightService insightService;

    @Mock
    private InsightStoreService insightStoreService;

    @Mock
    private GroupInsightStoreService groupInsightStoreService;

    private InsightGenerationService service;

    @BeforeEach
    void setUp() {
        service = new InsightGenerationService(
                groupResolutionService,
                contextReader,
                insightService,
                insightStoreService,
                groupInsightStoreService,
                FIXED_CLOCK,
                INTERVAL_MINUTES);
    }

    @Test
    void noGroupFallsThroughToTheUnchangedPerTickerBedrockPath() {
        when(groupResolutionService.resolve("RELIANCE.NS")).thenReturn(Optional.empty());
        InsightRequest request = request("RELIANCE.NS");
        InsightResponse expected = InsightResponse.builder()
                .ticker("RELIANCE.NS")
                .signal("BULLISH")
                .source("RULE_BASED")
                .build();
        when(insightService.generate(request)).thenReturn(expected);

        InsightResponse result = service.generate(request);

        assertThat(result).isSameAs(expected);
        verify(insightService).generate(request);
        verify(insightStoreService).store(expected);
        verify(contextReader, never()).buildContext(any(), any());
        verify(groupInsightStoreService, never()).latestGeneratedAt(any());
        verify(groupInsightStoreService, never()).store(any());
    }

    @Test
    void groupInsightYoungerThanIntervalIsSkippedCleanlyWithoutAnyWrites() {
        CorrelationGroup group = group("g1", "RELIANCE.NS", "TCS.NS");
        when(groupResolutionService.resolve("RELIANCE.NS")).thenReturn(Optional.of(group));
        // 10 minutes old: inside the 15-minute anti-spam window.
        when(groupInsightStoreService.latestGeneratedAt("g1"))
                .thenReturn(Optional.of(Instant.parse("2026-07-14T10:20:00Z")));

        InsightResponse result = service.generate(request("RELIANCE.NS"));

        assertThat(result.isSkipped()).isTrue();
        assertThat(result.getTicker()).isEqualTo("RELIANCE.NS");
        assertThat(result.isStored()).isFalse();
        verify(insightService, never()).generateForGroup(any(), any());
        verify(groupInsightStoreService, never()).store(any());
        verify(insightStoreService, never()).store(any());
    }

    @Test
    void groupInsightAtOrPastIntervalGeneratesAndStoresForEveryMember() {
        CorrelationGroup group = group("g1", "RELIANCE.NS", "TCS.NS");
        InsightRequest request = request("RELIANCE.NS");
        when(groupResolutionService.resolve("RELIANCE.NS")).thenReturn(Optional.of(group));
        // exactly 15 minutes old: at the boundary, must generate (not skip).
        when(groupInsightStoreService.latestGeneratedAt("g1"))
                .thenReturn(Optional.of(Instant.parse("2026-07-14T10:15:00Z")));
        GroupInsightContext context = new GroupInsightContext(
                "g1", List.of("RELIANCE.NS", "TCS.NS"), "RELIANCE.NS", null, List.of(), List.of(), "window");
        when(contextReader.buildContext(group, request)).thenReturn(context);
        GroupInsightResponse groupInsight = new GroupInsightResponse(
                "g1",
                List.of("RELIANCE.NS", "TCS.NS"),
                "2026-07-14T10:30:00Z",
                "BULLISH",
                0.6,
                "group rationale",
                List.of("driver"),
                "RULE_BASED",
                "test-model",
                "v2",
                "corr-1");
        when(insightService.generateForGroup(context, "corr-1")).thenReturn(groupInsight);

        InsightResponse result = service.generate(request);

        verify(groupInsightStoreService).store(groupInsight);
        ArgumentCaptor<InsightResponse> memberCaptor = ArgumentCaptor.forClass(InsightResponse.class);
        verify(insightStoreService, times(2)).store(memberCaptor.capture());
        List<String> storedTickers = memberCaptor.getAllValues().stream()
                .map(InsightResponse::getTicker)
                .toList();
        assertThat(storedTickers).containsExactlyInAnyOrder("RELIANCE.NS", "TCS.NS");
        memberCaptor.getAllValues().forEach(response -> {
            assertThat(response.getSignal()).isEqualTo("BULLISH");
            assertThat(response.getSource()).isEqualTo("RULE_BASED");
            assertThat(response.getGeneratedAt()).isEqualTo("2026-07-14T10:30:00Z");
        });

        assertThat(result.getTicker()).isEqualTo("RELIANCE.NS");
        assertThat(result.isSkipped()).isFalse();
        assertThat(result.getSignal()).isEqualTo("BULLISH");
    }

    @Test
    void groupWithNoPriorInsightGeneratesImmediately() {
        CorrelationGroup group = group("g1", "RELIANCE.NS");
        InsightRequest request = request("RELIANCE.NS");
        when(groupResolutionService.resolve("RELIANCE.NS")).thenReturn(Optional.of(group));
        when(groupInsightStoreService.latestGeneratedAt("g1")).thenReturn(Optional.empty());
        GroupInsightContext context = new GroupInsightContext(
                "g1", List.of("RELIANCE.NS"), "RELIANCE.NS", null, List.of(), List.of(), "window");
        when(contextReader.buildContext(group, request)).thenReturn(context);
        GroupInsightResponse groupInsight = new GroupInsightResponse(
                "g1",
                List.of("RELIANCE.NS"),
                "2026-07-14T10:30:00Z",
                "NEUTRAL",
                0.4,
                "r",
                List.of(),
                "RULE_BASED",
                "m",
                "v2",
                "corr-1");
        when(insightService.generateForGroup(eq(context), eq("corr-1"))).thenReturn(groupInsight);

        InsightResponse result = service.generate(request);

        assertThat(result.isSkipped()).isFalse();
        verify(groupInsightStoreService).store(groupInsight);
        verify(insightStoreService).store(any(InsightResponse.class));
    }

    private static InsightRequest request(String ticker) {
        InsightRequest request = new InsightRequest();
        request.setTicker(ticker);
        request.setCorrelationId("corr-1");
        return request;
    }

    private static CorrelationGroup group(String groupId, String... members) {
        return new CorrelationGroup(groupId, List.of(members), List.of(), "window", "computedAt");
    }
}
