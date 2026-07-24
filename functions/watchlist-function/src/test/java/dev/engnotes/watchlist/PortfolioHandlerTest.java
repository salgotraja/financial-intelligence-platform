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
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class PortfolioHandlerTest {

    private static final String DEFAULT_SUB = "dev-user";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Lot SAMPLE_LOT = new Lot(LocalDate.parse("2020-01-15"), 10, new BigDecimal("100.50"));
    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

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

    private Function<String, PortfolioResponse> handler() {
        return new PortfolioHandler()
                .portfolio(
                        store,
                        valuation,
                        consentGate,
                        FIXED_CLOCK,
                        DEFAULT_SUB,
                        historyService,
                        capture.metrics(),
                        MAPPER);
    }

    private static String json(PortfolioRequest request) {
        return MAPPER.writeValueAsString(request);
    }

    @Test
    void createUsesRequestSubWhenPresent() {
        PortfolioResponse response = handler()
                .apply(json(new PortfolioRequest(
                        PortfolioOperation.CREATE, "RELIANCE.NS", List.of(SAMPLE_LOT), "user-123", "corr-1")));

        verify(store).upsert("user-123", "RELIANCE.NS", List.of(SAMPLE_LOT));
        assertThat(response.status()).isEqualTo("created");
        assertThat(response.ticker()).isEqualTo("RELIANCE.NS");
    }

    @Test
    void createFallsBackToDefaultSubWhenRequestSubBlank() {
        handler()
                .apply(json(new PortfolioRequest(
                        PortfolioOperation.CREATE, "RELIANCE.NS", List.of(SAMPLE_LOT), null, "corr-1")));

        verify(store).upsert(DEFAULT_SUB, "RELIANCE.NS", List.of(SAMPLE_LOT));
    }

    @Test
    void createRejectsInvalidTickerWithoutTouchingStore() {
        assertThatThrownBy(() -> handler()
                        .apply(json(new PortfolioRequest(
                                PortfolioOperation.CREATE, "bad/ticker", List.of(SAMPLE_LOT), "user-123", "corr-2"))))
                .isInstanceOf(WatchlistException.class);
        verifyNoInteractions(store);
    }

    @Test
    void createRejectsFutureLotDateWithoutTouchingStore() {
        Lot futureLot = new Lot(LocalDate.parse("2026-07-24"), 10, new BigDecimal("100.50"));

        assertThatThrownBy(() -> handler()
                        .apply(json(new PortfolioRequest(
                                PortfolioOperation.CREATE, "RELIANCE.NS", List.of(futureLot), "user-123", "corr-3"))))
                .isInstanceOf(WatchlistException.class);
        verifyNoInteractions(store);
    }

    @Test
    void createRefusesWhenDeletionPending() {
        when(consentGate.isDeletionPending("user-123")).thenReturn(true);

        assertThatThrownBy(() -> handler()
                        .apply(json(new PortfolioRequest(
                                PortfolioOperation.CREATE, "RELIANCE.NS", List.of(SAMPLE_LOT), "user-123", "corr-4"))))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("deletion pending");
        verifyNoInteractions(store);
    }

    @Test
    void deleteUsesRequestSub() {
        PortfolioResponse response = handler()
                .apply(json(new PortfolioRequest(PortfolioOperation.DELETE, "TCS.NS", null, "user-123", "corr-5")));

        verify(store).delete("user-123", "TCS.NS");
        assertThat(response.status()).isEqualTo("deleted");
    }

    @Test
    void deleteIgnoresDeletionPending() {
        PortfolioResponse response = handler()
                .apply(json(
                        new PortfolioRequest(PortfolioOperation.DELETE, "RELIANCE.NS", null, "user-123", "corr-6")));

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

        PortfolioResponse response =
                handler().apply(json(new PortfolioRequest(PortfolioOperation.LIST, null, null, "user-123", "corr-7")));

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.portfolio()).isSameAs(stubbedValuation);
    }

    @Test
    void historyReturnsSeries() {
        PortfolioHistory stubbedHistory = new PortfolioHistory(
                "2026-07-20", "2026-07-23", List.of(), List.of(), List.of(), List.of(), null, null);
        when(historyService.history("user-123")).thenReturn(stubbedHistory);

        PortfolioResponse response = handler()
                .apply(json(new PortfolioRequest(PortfolioOperation.HISTORY, null, null, "user-123", "corr-10")));

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.history()).isSameAs(stubbedHistory);
    }

    @Test
    void deniesEveryOperationWithoutActiveConsent() {
        when(consentGate.isActive("user-123")).thenReturn(false);

        assertThatThrownBy(() -> handler()
                        .apply(json(new PortfolioRequest(PortfolioOperation.LIST, null, null, "user-123", "corr-8"))))
                .isInstanceOf(WatchlistException.class);
        verifyNoInteractions(store);
        assertThat(capture.records()).anySatisfy(record -> assertThat(record).contains("\"ConsentBlocked\""));
    }

    @Test
    void checksConsentForTheResolvedOwner() {
        when(consentGate.isActive("user-123")).thenReturn(true);

        handler()
                .apply(json(new PortfolioRequest(
                        PortfolioOperation.CREATE, "RELIANCE.NS", List.of(SAMPLE_LOT), "user-123", "corr-9")));

        verify(consentGate).isActive("user-123");
        verify(store).upsert("user-123", "RELIANCE.NS", List.of(SAMPLE_LOT));
    }

    @Test
    void createWithEmptyBuyDateMapsToInvalidRequestBody() {
        String malformed = """
                {"operation":"CREATE","ticker":"RELIANCE.NS","lots":[{"buyDate":"","qty":10,"price":100.50}],"ownerSub":"user-123","correlationId":"corr-11"}""";

        assertThatThrownBy(() -> handler().apply(malformed))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("invalid request body");
        verifyNoInteractions(store);
    }

    @Test
    void createWithMissingQtyMapsToInvalidRequestBody() {
        String malformed = """
                {"operation":"CREATE","ticker":"RELIANCE.NS","lots":[{"buyDate":"2020-01-15","price":100.50}],"ownerSub":"user-123","correlationId":"corr-12"}""";

        assertThatThrownBy(() -> handler().apply(malformed))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("invalid request body");
        verifyNoInteractions(store);
    }

    @Test
    void createWithNonNumericPriceMapsToInvalidRequestBody() {
        String malformed = """
                {"operation":"CREATE","ticker":"RELIANCE.NS","lots":[{"buyDate":"2020-01-15","qty":10,"price":"free"}],"ownerSub":"user-123","correlationId":"corr-13"}""";

        assertThatThrownBy(() -> handler().apply(malformed))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("invalid request body");
        verifyNoInteractions(store);
    }

    @Test
    void createWithMissingLotsMapsToInvalidRequestBody() {
        String malformed = """
                {"operation":"CREATE","ticker":"RELIANCE.NS","ownerSub":"user-123","correlationId":"corr-14"}""";

        // lots absent deserializes to null, which is a valid PortfolioRequest -> the handler's own
        // null-lots WatchlistException fires, not the deserialization path. Its message must carry
        // the "invalid request body" token so QueryStack's CLIENT_ERROR_PATTERN maps it to 400, not
        // an opaque 500.
        assertThatThrownBy(() -> handler().apply(malformed))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("invalid request body")
                .hasMessageContaining("holding lots must not be null");
        verifyNoInteractions(store);
    }

    @Test
    void createWithZeroPriceMapsToInvalidRequestBody() {
        String malformed = """
                {"operation":"CREATE","ticker":"RELIANCE.NS","lots":[{"buyDate":"2020-01-15","qty":10,"price":0}],"ownerSub":"user-123","correlationId":"corr-15"}""";

        assertThatThrownBy(() -> handler().apply(malformed))
                .isInstanceOf(WatchlistException.class)
                .hasMessageContaining("invalid request body");
        verifyNoInteractions(store);
    }

    @Test
    void validJsonBodyRoundTripsThroughStringBean() {
        PortfolioResponse response = handler()
                .apply(json(new PortfolioRequest(
                        PortfolioOperation.CREATE, "RELIANCE.NS", List.of(SAMPLE_LOT), "user-123", "corr-16")));

        assertThat(response.status()).isEqualTo("created");
        verify(store).upsert("user-123", "RELIANCE.NS", List.of(SAMPLE_LOT));
    }
}
