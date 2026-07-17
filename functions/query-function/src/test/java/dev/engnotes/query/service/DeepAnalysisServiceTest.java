package dev.engnotes.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.engnotes.query.model.DailyMarketDataResponse;
import dev.engnotes.query.model.DailyPoint;
import dev.engnotes.query.model.DeepAnalysisResponse;
import dev.engnotes.query.model.HorizonStats;
import dev.engnotes.query.model.MarketDataPoint;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeepAnalysisServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-17T10:00:00Z");

    @Mock
    private DailyMarketDataQuery dailyMarketDataQuery;

    @Mock
    private MarketDataQuery marketDataQuery;

    private DeepAnalysisService service;

    @BeforeEach
    void setUp() {
        service =
                new DeepAnalysisService(dailyMarketDataQuery, marketDataQuery, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    // Newest-first, matching DailyMarketDataQuery ordering. Closes oldest->newest:
    // 100, 102, 101, 104, 103, 106. Volumes all 1000 except the newest day 1500.
    private static List<DailyPoint> sixDays() {
        String[] dates = {"2026-07-10", "2026-07-11", "2026-07-14", "2026-07-15", "2026-07-16", "2026-07-17"};
        String[] closes = {"100", "102", "101", "104", "103", "106"};
        List<DailyPoint> ascending = new ArrayList<>();
        for (int i = 0; i < dates.length; i++) {
            long volume = i == dates.length - 1 ? 1500L : 1000L;
            ascending.add(new DailyPoint(
                    dates[i],
                    new BigDecimal(closes[i]),
                    new BigDecimal(closes[i]).add(new BigDecimal("2")),
                    new BigDecimal(closes[i]).subtract(new BigDecimal("2")),
                    new BigDecimal(closes[i]),
                    null,
                    volume));
        }
        return ascending.reversed(); // service receives newest-first
    }

    private HorizonStats horizon(DeepAnalysisResponse response, String key) {
        return response.horizons().stream()
                .filter(h -> h.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void computesTheFullStatsPackForTheWeekHorizon() {
        when(dailyMarketDataQuery.findDailyPoints("INFY.NS", "260"))
                .thenReturn(new DailyMarketDataResponse("INFY.NS", sixDays(), true));
        when(marketDataQuery.findLatestPoint("INFY.NS")).thenReturn(Optional.empty());

        DeepAnalysisResponse response = service.analyze("INFY.NS");

        assertThat(response.found()).isTrue();
        assertThat(response.generatedAt()).isEqualTo(FIXED_NOW.toString());
        assertThat(response.horizons()).extracting(HorizonStats::key).containsExactly("1W", "1M", "3M", "1Y");

        HorizonStats week = horizon(response, "1W");
        // 1W window = last 6 rows (5 returns): closes 100,102,101,104,103,106
        assertThat(week.daysAvailable()).isEqualTo(6);
        assertThat(week.partial()).isFalse();
        assertThat(week.returnPercent()).isEqualByComparingTo("6.00");
        assertThat(week.high()).isEqualByComparingTo("108"); // day-high = close+2 on the 106 day
        assertThat(week.low()).isEqualByComparingTo("98"); // day-low = close-2 on the 100 day
        // daily returns %: +2.00, -0.98, +2.97, -0.96, +2.91 -> population stdev 1.80
        assertThat(week.volatilityPercent()).isEqualByComparingTo("1.80");
        // running peaks 100,102,102,104,104,106 -> deepest trough 101 vs 102 = 0.98%
        assertThat(week.maxDrawdownPercent()).isEqualByComparingTo("0.98");
        assertThat(week.bestDay().date()).isEqualTo("2026-07-15");
        assertThat(week.bestDay().changePercent()).isEqualByComparingTo("2.97");
        assertThat(week.worstDay().date()).isEqualTo("2026-07-14");
        assertThat(week.worstDay().changePercent()).isEqualByComparingTo("-0.98");
        assertThat(week.upDays()).isEqualTo(3);
        assertThat(week.downDays()).isEqualTo(2);
        assertThat(week.avgVolume()).isEqualTo(1083L); // mean of 5x1000 + 1500, floored
        // last-5-day mean 1100 vs window mean 1083.33 -> +1.54%
        assertThat(week.volumeTrendPercent()).isEqualByComparingTo("1.54");
    }

    @Test
    void horizonsBeyondAvailableHistoryAreMarkedPartial() {
        when(dailyMarketDataQuery.findDailyPoints("INFY.NS", "260"))
                .thenReturn(new DailyMarketDataResponse("INFY.NS", sixDays(), true));
        when(marketDataQuery.findLatestPoint("INFY.NS")).thenReturn(Optional.empty());

        DeepAnalysisResponse response = service.analyze("INFY.NS");

        HorizonStats month = horizon(response, "1M");
        assertThat(month.partial()).isTrue();
        assertThat(month.daysAvailable()).isEqualTo(6);
        assertThat(month.returnPercent()).isEqualByComparingTo("6.00"); // computed over what exists
        HorizonStats year = horizon(response, "1Y");
        assertThat(year.partial()).isTrue();
    }

    @Test
    void bandUsesLatestPointFiftyTwoWeekFieldsWhenPresent() {
        when(dailyMarketDataQuery.findDailyPoints("INFY.NS", "260"))
                .thenReturn(new DailyMarketDataResponse("INFY.NS", sixDays(), true));
        when(marketDataQuery.findLatestPoint("INFY.NS"))
                .thenReturn(Optional.of(new MarketDataPoint(
                        "2026-07-17T09:59:00Z",
                        new BigDecimal("106"),
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("130"),
                        new BigDecimal("90"))));

        DeepAnalysisResponse response = service.analyze("INFY.NS");

        assertThat(response.band52w().source()).isEqualTo("HIGH_LOW_52W");
        assertThat(response.band52w().high()).isEqualByComparingTo("130");
        assertThat(response.band52w().low()).isEqualByComparingTo("90");
        // (106 - 90) / (130 - 90) = 40%
        assertThat(response.band52w().bandPositionPercent()).isEqualByComparingTo("40.00");
    }

    @Test
    void bandFallsBackToTheYearWindowWhenNoLatestPoint() {
        when(dailyMarketDataQuery.findDailyPoints("INFY.NS", "260"))
                .thenReturn(new DailyMarketDataResponse("INFY.NS", sixDays(), true));
        when(marketDataQuery.findLatestPoint("INFY.NS")).thenReturn(Optional.empty());

        DeepAnalysisResponse response = service.analyze("INFY.NS");

        assertThat(response.band52w().source()).isEqualTo("DERIVED_1Y");
        assertThat(response.band52w().high()).isEqualByComparingTo("108");
        assertThat(response.band52w().low()).isEqualByComparingTo("98");
        // (106 - 98) / (108 - 98) = 80%
        assertThat(response.band52w().bandPositionPercent()).isEqualByComparingTo("80.00");
    }

    @Test
    void constantSeriesHasZeroVolatilityAndZeroDrawdown() {
        List<DailyPoint> flat = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            flat.add(new DailyPoint("2026-07-1" + i, null, null, null, new BigDecimal("100"), null, null));
        }
        when(dailyMarketDataQuery.findDailyPoints("FLAT.NS", "260"))
                .thenReturn(new DailyMarketDataResponse("FLAT.NS", flat, true));
        when(marketDataQuery.findLatestPoint("FLAT.NS")).thenReturn(Optional.empty());

        DeepAnalysisResponse response = service.analyze("FLAT.NS");

        HorizonStats week = horizon(response, "1W");
        assertThat(week.returnPercent()).isEqualByComparingTo("0.00");
        assertThat(week.volatilityPercent()).isEqualByComparingTo("0.00");
        assertThat(week.maxDrawdownPercent()).isEqualByComparingTo("0.00");
        assertThat(week.upDays()).isZero();
        assertThat(week.downDays()).isZero();
        assertThat(week.avgVolume()).isNull(); // no volumes seeded
        assertThat(week.volumeTrendPercent()).isNull();
        // band position is null when high == low
        assertThat(response.band52w().bandPositionPercent()).isNull();
    }

    @Test
    void noHistoryReturnsNotFound() {
        when(dailyMarketDataQuery.findDailyPoints("EMPTY.NS", "260"))
                .thenReturn(DailyMarketDataResponse.notFound("EMPTY.NS"));

        DeepAnalysisResponse response = service.analyze("EMPTY.NS");

        assertThat(response.found()).isFalse();
        assertThat(response.horizons()).isEmpty();
        assertThat(response.band52w()).isNull();
    }

    @Test
    void singleRowIsPartialWithNoStats() {
        List<DailyPoint> one =
                List.of(new DailyPoint("2026-07-17", null, null, null, new BigDecimal("100"), null, null));
        when(dailyMarketDataQuery.findDailyPoints("NEW.NS", "260"))
                .thenReturn(new DailyMarketDataResponse("NEW.NS", one, true));
        when(marketDataQuery.findLatestPoint("NEW.NS")).thenReturn(Optional.empty());

        DeepAnalysisResponse response = service.analyze("NEW.NS");

        assertThat(response.found()).isTrue();
        HorizonStats week = horizon(response, "1W");
        assertThat(week.partial()).isTrue();
        assertThat(week.daysAvailable()).isEqualTo(1);
        assertThat(week.returnPercent()).isNull(); // fewer than 2 usable rows: no stats, no pretending
    }
}
