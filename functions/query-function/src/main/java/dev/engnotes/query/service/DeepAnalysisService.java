package dev.engnotes.query.service;

import dev.engnotes.query.model.Band52w;
import dev.engnotes.query.model.DailyPoint;
import dev.engnotes.query.model.DayMove;
import dev.engnotes.query.model.DeepAnalysisResponse;
import dev.engnotes.query.model.HorizonStats;
import dev.engnotes.query.model.MarketDataPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deterministic multi-horizon stats over DAY# history (spec 2026-07-17): per horizon the return,
 * range, volatility, max drawdown, best/worst day, up/down counts, and volume trend, plus the
 * 52-week band. Pure math on data {@link DailyMarketDataQuery} already serves - no Bedrock, no
 * writes, no prose (narrative stays in the {@link StoryComposer} seam). Computation is on-read:
 * ~260 projected rows are one cheap query page and the API Gateway cache absorbs repeats.
 */
@Service
public class DeepAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DeepAnalysisService.class);

    private record Horizon(String key, int tradingDays) {}

    // Trading-day windows; 250 ~= one NSE year. The fetch depth must cover the largest window
    // plus one row (N returns need N+1 closes).
    private static final List<Horizon> HORIZONS =
            List.of(new Horizon("1W", 5), new Horizon("1M", 22), new Horizon("3M", 66), new Horizon("1Y", 250));
    private static final String FETCH_DAYS = "260";
    private static final int RECENT_VOLUME_DAYS = 5;
    private static final int SCALE = 2;

    private final DailyMarketDataQuery dailyMarketDataQuery;
    private final MarketDataQuery marketDataQuery;
    private final Clock clock;

    public DeepAnalysisService(
            DailyMarketDataQuery dailyMarketDataQuery, MarketDataQuery marketDataQuery, Clock clock) {
        this.dailyMarketDataQuery = dailyMarketDataQuery;
        this.marketDataQuery = marketDataQuery;
        this.clock = clock;
    }

    public DeepAnalysisResponse analyze(String rawTicker) {
        var daily = dailyMarketDataQuery.findDailyPoints(rawTicker, FETCH_DAYS);
        String ticker = daily.ticker();
        String generatedAt = Instant.now(clock).toString();
        if (!daily.found()) {
            log.info("No history for deep analysis. ticker={}", ticker);
            return DeepAnalysisResponse.notFound(ticker, generatedAt);
        }

        // Ascending (oldest first) rows with a non-null close: every stat derives from closes.
        List<DailyPoint> ascending =
                daily.days().reversed().stream().filter(d -> d.close() != null).toList();

        List<HorizonStats> horizons = new ArrayList<>();
        for (Horizon horizon : HORIZONS) {
            horizons.add(computeHorizon(horizon, ascending));
        }
        Band52w band = band52w(ticker, ascending);

        log.info("Deep analysis computed. ticker={} rows={} band={}", ticker, ascending.size(), band.source());
        return new DeepAnalysisResponse(ticker, generatedAt, horizons, band, true);
    }

    private HorizonStats computeHorizon(Horizon horizon, List<DailyPoint> ascending) {
        int needed = horizon.tradingDays() + 1;
        boolean partial = ascending.size() < needed;
        List<DailyPoint> window = ascending.subList(Math.max(0, ascending.size() - needed), ascending.size());
        int daysAvailable = window.size();
        if (daysAvailable < 2) {
            return new HorizonStats(
                    horizon.key(), daysAvailable, true, null, null, null, null, null, null, null, 0, 0, null, null);
        }

        double firstClose = window.getFirst().close().doubleValue();
        double lastClose = window.getLast().close().doubleValue();
        BigDecimal returnPercent = pct((lastClose - firstClose) / firstClose * 100);

        BigDecimal high = window.stream()
                .map(d -> d.high() != null ? d.high() : d.close())
                .max(BigDecimal::compareTo)
                .orElse(null);
        BigDecimal low = window.stream()
                .map(d -> d.low() != null ? d.low() : d.close())
                .min(BigDecimal::compareTo)
                .orElse(null);

        // Daily close-to-close returns over the window.
        int returnsCount = daysAvailable - 1;
        double[] returns = new double[returnsCount];
        DayMove best = null;
        DayMove worst = null;
        int upDays = 0;
        int downDays = 0;
        double peak = window.getFirst().close().doubleValue();
        double maxDrawdown = 0;
        for (int i = 1; i < daysAvailable; i++) {
            double prev = window.get(i - 1).close().doubleValue();
            double curr = window.get(i).close().doubleValue();
            double r = (curr - prev) / prev * 100;
            returns[i - 1] = r;
            if (r > 0) {
                upDays++;
            } else if (r < 0) {
                downDays++;
            }
            if (best == null || r > best.changePercent().doubleValue()) {
                best = new DayMove(window.get(i).date(), pct(r));
            }
            if (worst == null || r < worst.changePercent().doubleValue()) {
                worst = new DayMove(window.get(i).date(), pct(r));
            }
            peak = Math.max(peak, curr);
            maxDrawdown = Math.max(maxDrawdown, (peak - curr) / peak * 100);
        }
        double mean = 0;
        for (double r : returns) {
            mean += r;
        }
        mean /= returnsCount;
        double variance = 0;
        for (double r : returns) {
            variance += (r - mean) * (r - mean);
        }
        variance /= returnsCount;
        BigDecimal volatility = pct(Math.sqrt(variance));

        List<Long> volumes =
                window.stream().map(DailyPoint::volume).filter(Objects::nonNull).toList();
        Long avgVolume = volumes.isEmpty()
                ? null
                : (long) volumes.stream().mapToLong(Long::longValue).average().orElse(0);
        BigDecimal volumeTrend = null;
        if (volumes.size() >= 2) {
            List<Long> recent = volumes.subList(Math.max(0, volumes.size() - RECENT_VOLUME_DAYS), volumes.size());
            double recentMean =
                    recent.stream().mapToLong(Long::longValue).average().orElse(0);
            double windowMean =
                    volumes.stream().mapToLong(Long::longValue).average().orElse(0);
            if (windowMean > 0) {
                volumeTrend = pct((recentMean / windowMean - 1) * 100);
            }
        }

        return new HorizonStats(
                horizon.key(),
                daysAvailable,
                partial,
                returnPercent,
                high,
                low,
                volatility,
                pct(maxDrawdown),
                best,
                worst,
                upDays,
                downDays,
                avgVolume,
                volumeTrend);
    }

    private Band52w band52w(String ticker, List<DailyPoint> ascending) {
        Optional<MarketDataPoint> latest = marketDataQuery.findLatestPoint(ticker);
        BigDecimal high;
        BigDecimal low;
        String source;
        if (latest.isPresent()
                && latest.get().high52Week() != null
                && latest.get().low52Week() != null) {
            high = latest.get().high52Week();
            low = latest.get().low52Week();
            source = "HIGH_LOW_52W";
        } else {
            high = ascending.stream()
                    .map(d -> d.high() != null ? d.high() : d.close())
                    .max(BigDecimal::compareTo)
                    .orElse(null);
            low = ascending.stream()
                    .map(d -> d.low() != null ? d.low() : d.close())
                    .min(BigDecimal::compareTo)
                    .orElse(null);
            source = "DERIVED_1Y";
        }
        BigDecimal position = null;
        if (high != null && low != null && high.compareTo(low) != 0 && !ascending.isEmpty()) {
            double h = high.doubleValue();
            double l = low.doubleValue();
            double lastClose = ascending.getLast().close().doubleValue();
            position = pct((lastClose - l) / (h - l) * 100);
        }
        return new Band52w(high, low, position, source);
    }

    private static BigDecimal pct(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
