package dev.engnotes.insight.service;

import dev.engnotes.insight.model.CorrelationEdge;
import dev.engnotes.insight.model.CorrelationGroup;
import dev.engnotes.insight.model.CorrelationResponse;
import dev.engnotes.insight.model.TickerSeries;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one correlation pass (spec section 7): read the WATCHSET union, read each ticker's
 * recent return series, compute pairwise Pearson correlation, threshold-cluster into groups, and
 * persist. Holds no AWS client directly - {@link CorrelationDataReader} and
 * {@link CorrelationStoreService} own the DynamoDB access, so this class is pure orchestration and
 * testable with mocks of those two collaborators.
 */
@Service
public class CorrelationService {

    private static final Logger log = LoggerFactory.getLogger(CorrelationService.class);

    /** Per-ticker read window: up to 30 recent priced points (spec section 7). */
    static final int WINDOW_SIZE = 30;

    /** Minimum common buckets (own history, and pairwise overlap) before a correlation counts. */
    static final int MIN_ALIGNED_POINTS = 10;

    private final CorrelationDataReader dataReader;
    private final CorrelationStoreService storeService;
    private final double threshold;

    public CorrelationService(
            CorrelationDataReader dataReader,
            CorrelationStoreService storeService,
            @Value("${CORRELATION_THRESHOLD:0.6}") double threshold) {
        this.dataReader = dataReader;
        this.storeService = storeService;
        this.threshold = threshold;
    }

    public CorrelationResponse compute(Instant now) {
        Map<String, TickerSeries> qualifying = readQualifyingSeries();
        List<CorrelationEdge> computedEdges = computeAllPairs(qualifying);
        List<CorrelationEdge> qualifyingEdges = computedEdges.stream()
                .filter(edge -> Math.abs(edge.rho()) >= threshold)
                .toList();

        String computedAt = now.toString();
        String window = windowDescription();

        List<CorrelationGroup> groups = CorrelationClustering.connectedComponents(qualifyingEdges).stream()
                .map(members -> new CorrelationGroup(
                        GroupIdGenerator.groupId(members),
                        members,
                        rhosWithin(computedEdges, members),
                        window,
                        computedAt))
                .toList();

        storeService.replaceAll(groups);

        log.info(
                "Correlation pass complete. tickersEvaluated={} pairsComputed={} groupsComputed={} threshold={}",
                qualifying.size(),
                computedEdges.size(),
                groups.size(),
                threshold);

        return new CorrelationResponse("computed", qualifying.size(), groups.size(), computedAt);
    }

    /** Reads every WATCHSET ticker's series, keeping only those with enough of their own history. */
    private Map<String, TickerSeries> readQualifyingSeries() {
        Map<String, TickerSeries> qualifying = new LinkedHashMap<>();
        for (String ticker : dataReader.watchsetTickers()) {
            TickerSeries series = dataReader.readSeries(ticker, WINDOW_SIZE);
            if (series.buckets().size() < MIN_ALIGNED_POINTS) {
                log.info(
                        "Ticker has insufficient points for correlation. ticker={} points={}",
                        ticker,
                        series.buckets().size());
                continue;
            }
            qualifying.put(ticker, series);
        }
        return qualifying;
    }

    private static List<CorrelationEdge> computeAllPairs(Map<String, TickerSeries> qualifying) {
        List<String> tickers = qualifying.keySet().stream().sorted().toList();
        List<CorrelationEdge> edges = new ArrayList<>();
        for (int i = 0; i < tickers.size(); i++) {
            for (int j = i + 1; j < tickers.size(); j++) {
                String tickerA = tickers.get(i);
                String tickerB = tickers.get(j);
                CorrelationMath.correlate(qualifying.get(tickerA), qualifying.get(tickerB), MIN_ALIGNED_POINTS)
                        .ifPresent(rho -> edges.add(new CorrelationEdge(tickerA, tickerB, rho)));
            }
        }
        return edges;
    }

    private static List<CorrelationEdge> rhosWithin(List<CorrelationEdge> edges, List<String> members) {
        Set<String> memberSet = Set.copyOf(members);
        return edges.stream()
                .filter(edge -> memberSet.contains(edge.tickerA()) && memberSet.contains(edge.tickerB()))
                .toList();
    }

    private String windowDescription() {
        return "%d-point window, 1-minute bucket, min %d aligned points, |rho|>=%.2f"
                .formatted(WINDOW_SIZE, MIN_ALIGNED_POINTS, threshold);
    }
}
