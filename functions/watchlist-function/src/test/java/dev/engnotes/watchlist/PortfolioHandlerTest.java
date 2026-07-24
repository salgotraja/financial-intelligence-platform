package dev.engnotes.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.engnotes.observability.Metrics;
import dev.engnotes.watchlist.exception.WatchlistException;
import dev.engnotes.watchlist.model.Lot;
import dev.engnotes.watchlist.model.PortfolioHistory;
import dev.engnotes.watchlist.model.PortfolioOperation;
import dev.engnotes.watchlist.model.PortfolioRequest;
import dev.engnotes.watchlist.model.PortfolioResponse;
import dev.engnotes.watchlist.model.PortfolioValuation;
import dev.engnotes.watchlist.service.ConsentGate;
import dev.engnotes.watchlist.service.HoldingsStoreService;
import dev.engnotes.watchlist.service.PortfolioHistoryService;
import dev.engnotes.watchlist.service.ValuationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortfolioHandlerTest {

    private static final String DEFAULT_SUB = "dev-user";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Lot SAMPLE_LOT = new Lot(LocalDate.parse("2020-01-15"), 10, new BigDecimal("100.50"));

    @Mock
    private HoldingsStoreService store;

    @Mock
    private ValuationService valuation;

    @Mock
    private ConsentGate consentGate;

    @Mock
    private PortfolioHistoryService historyService;

    private final Metrics.Capture capture = Metrics.forTesting();

    @BeforeEach
    void allowConsentByDefault() {
        lenient()
                .when(consentGate.isActive(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        lenient()
                .when(consentGate.isDeletionPending(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
    }

    @Test
    void createUsesRequestSubWhenPresent() {
        PortfolioResponse response = new PortfolioHandler()
                .portfolio(store, valuation, consentGate, FIXED_CLOCK, DEFAULT_SUB, historyService, capture.metrics())
                .apply(new PortfolioRequest(
                        PortfolioOperation.CREATE, "RELIANCE.NS", List.of(SAMPLE_LOT), "user-123", "corr-1"));

        verify(store).upsert("user-123", "RELIANCE.NS", List.of(SAMPLE_LOT));
        assertThat(response.status()).isEqualTo("created");
        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
    }

    @Test
    void createFallsBackToDefaultSubWhenRequestSubBlank() {
        new PortfolioHandler()
                .portfolio(store, valuation, consentGate, FIXED_CLOCK, DEFAULT_SUB, historyService, capture.metrics())
                .apply(new PortfolioRequest(
                        PortfolioOperation.CREATE, "RELIANCE.NS", List.of(SAMPLE_LOT), null, "corr-1"));

        verify(store).upsert(DEFAULT_SUB, "RELIANCE.NS", List.of(SAMPLE_LOT));
    }

    @Test
    void createRejectsInvalidTickerWithoutTouchingStore() {
        assertThatThrownBy(() -> new PortfolioHandler()
                        .portfolio(
                                store,
                                valuation,
                                consentGate,
                                FIXED_CLOCK,
                                DEFAULT_SUB,
                                historyService,
                                capture.metrics())
                        .apply(new PortfolioRequest(
                                PortfolioOperation.CREATE, "bad/ticker", List.of(SAMPLE_LOT), "user-123", "corr-2")))
                .isInstanceOf(WatchlistException.class);
        verifyNoInteractions(store);
    }

    @Test
    void createRejectsFutureLotDateWithoutTouchingStore() {
        Lot futureLot = new Lot(LocalDate.parse("2026-07-24"), 10, new BigDecimal("100.50"));

        assertThatThrownBy(() -> new PortfolioHandler()
                        .portfolio(
                                store,
                                valuation,
                                consentGate,
                                FIXED_CLOCK,
                                DEFAULT_SUB,
                                historyService,
                                capture.metrics())
                        .apply(new PortfolioRequest(
                                PortfolioOperation.CREATE, "RELIANCE.NS", List.of(futureLot), "user-123", "corr-3")))
                .isInstanceOf(WatchlistException.class);
        verifyNoInteractions(store);
    }

    @Test
    void createRefusesWhenDeletionPending() {
        when(consentGate.isDeletionPending("user-123")).thenReturn(true);

        assertThatThrownBy(() -> new PortfolioHandler()
                        .portfolio(
                                store,
                                valuation,
                                consentGate,
                                FIXED_CLOCK,
                                DEFAULT_SUB,
                                historyService,
                                capture.metrics())
                        .apply(new PortfolioRequest(
                                PortfolioOperation.CREATE, "RELIANCE.NS", List.of(SAMPLE_LOT), "user-123", "corr-4")))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("deletion pending");
        verifyNoInteractions(store);
    }

    @Test
    void deleteUsesRequestSub() {
        PortfolioResponse response = new PortfolioHandler()
                .portfolio(store, valuation, consentGate, FIXED_CLOCK, DEFAULT_SUB, historyService, capture.metrics())
                .apply(new PortfolioRequest(PortfolioOperation.DELETE, "TCS.NS", null, "user-123", "corr-5"));

        verify(store).delete("user-123", "TCS.NS");
        assertThat(response.status()).isEqualTo("deleted");
    }

    @Test
    void deleteIgnoresDeletionPending() {
        PortfolioResponse response = new PortfolioHandler()
                .portfolio(store, valuation, consentGate, FIXED_CLOCK, DEFAULT_SUB, historyService, capture.metrics())
                .apply(new PortfolioRequest(PortfolioOperation.DELETE, "RELIANCE.NS", null, "user-123", "corr-6"));

        assertThat(response.status()).isEqualTo("deleted");
        verify(store).delete("user-123", "RELIANCE.NS");
        verify(consentGate, org.mockito.Mockito.never()).isDeletionPending(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listReturnsValuation() {
        PortfolioValuation stubbedValuation = new PortfolioValuation(
                "2026-07-23T00:00:00Z",
                new BigDecimal("1200.00"),
                new BigDecimal("1000.00"),
                new BigDecimal("200.00"),
                new BigDecimal("80.00"),
                List.of());
        when(valuation.value("user-123")).thenReturn(stubbedValuation);

        PortfolioResponse response = new PortfolioHandler()
                .portfolio(store, valuation, consentGate, FIXED_CLOCK, DEFAULT_SUB, historyService, capture.metrics())
                .apply(new PortfolioRequest(PortfolioOperation.LIST, null, null, "user-123", "corr-7"));

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.portfolio()).isSameAs(stubbedValuation);
    }

    @Test
    void historyReturnsSeries() {
        PortfolioHistory stubbedHistory = new PortfolioHistory(
                "2026-07-20", "2026-07-23", List.of(), List.of(), List.of(), List.of(), null, null);
        when(historyService.history("user-123")).thenReturn(stubbedHistory);

        PortfolioResponse response = new PortfolioHandler()
                .portfolio(store, valuation, consentGate, FIXED_CLOCK, DEFAULT_SUB, historyService, capture.metrics())
                .apply(new PortfolioRequest(PortfolioOperation.HISTORY, null, null, "user-123", "corr-10"));

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.history()).isSameAs(stubbedHistory);
    }

    @Test
    void deniesEveryOperationWithoutActiveConsent() {
        when(consentGate.isActive("user-123")).thenReturn(false);

        assertThatThrownBy(() -> new PortfolioHandler()
                        .portfolio(
                                store,
                                valuation,
                                consentGate,
                                FIXED_CLOCK,
                                DEFAULT_SUB,
                                historyService,
                                capture.metrics())
                        .apply(new PortfolioRequest(PortfolioOperation.LIST, null, null, "user-123", "corr-8")))
                .isInstanceOf(WatchlistException.class);
        verifyNoInteractions(store);
        assertThat(capture.records()).anySatisfy(record -> assertThat(record).contains("\"ConsentBlocked\""));
    }

    @Test
    void checksConsentForTheResolvedOwner() {
        when(consentGate.isActive("user-123")).thenReturn(true);

        new PortfolioHandler()
                .portfolio(store, valuation, consentGate, FIXED_CLOCK, DEFAULT_SUB, historyService, capture.metrics())
                .apply(new PortfolioRequest(
                        PortfolioOperation.CREATE, "RELIANCE.NS", List.of(SAMPLE_LOT), "user-123", "corr-9"));

        verify(consentGate).isActive("user-123");
        verify(store).upsert("user-123", "RELIANCE.NS", List.of(SAMPLE_LOT));
    }
}
