package dev.engnotes.watchlist;

import dev.engnotes.observability.Metrics;
import dev.engnotes.observability.RequestContext;
import dev.engnotes.watchlist.exception.WatchlistException;
import dev.engnotes.watchlist.model.PortfolioRequest;
import dev.engnotes.watchlist.model.PortfolioResponse;
import dev.engnotes.watchlist.portfolio.PortfolioValidator;
import dev.engnotes.watchlist.service.ConsentGate;
import dev.engnotes.watchlist.service.HoldingsStoreService;
import dev.engnotes.watchlist.service.PortfolioHistoryService;
import dev.engnotes.watchlist.service.ValuationService;
import dev.engnotes.watchlist.validation.TickerValidator;
import java.time.Clock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Portfolio Lambda - Spring Cloud Function entry point (write path). Component-scanned by {@link
 * WatchlistHandler}; deployed as its own Lambda from the same jar.
 *
 * <p>The bean name ("portfolio") must match SPRING_CLOUD_FUNCTION_DEFINITION in QueryStack for the
 * portfolio Lambda. API Gateway maps each HTTP method/route to this function via the integration
 * request template, setting {@code operation} (POST -> CREATE, GET -> LIST, DELETE -> DELETE, GET
 * /portfolio/history -> HISTORY), {@code ticker} from the path, {@code lots} from the body (CREATE
 * only), and {@code ownerSub} from the authorizer context ($context.authorizer.sub). HISTORY is
 * consent-gated like every other operation but, being a read path, is not deletion-pending gated.
 *
 * <p>Validates the ticker at the trust boundary (spec section 12) before any write, resolves the
 * owner sub (request sub, else the DEFAULT_OWNER_SUB fallback for unauthenticated local runs), then
 * dispatches. Items are keyed under {@code USER#{sub}}/{@code HOLDING#{ticker}} so the DPDP erasure
 * cascade stays a prefix delete.
 */
@Configuration
public class PortfolioHandler {

    private static final Logger log = LoggerFactory.getLogger(PortfolioHandler.class);

    static final String MODULE_LABEL = WatchlistHandler.MODULE_LABEL;

    // Spring Cloud Function's JsonMessageConverter deserializes the declared input type BEFORE the
    // bean runs, but swallows any deserialization exception (catches all, logs DEBUG, returns null)
    // and passes the raw byte[] through instead - a malformed body then reaches the bean as bytes,
    // fails the cast, and API Gateway maps the resulting ClassCastException to an opaque 500. Taking
    // the input as a raw String and deserializing it ourselves lets us turn that failure into a
    // WatchlistException with the "invalid request body" token QueryStack maps to 400.
    @Bean
    public Function<String, PortfolioResponse> portfolio(
            HoldingsStoreService store,
            ValuationService valuation,
            ConsentGate consentGate,
            Clock clock,
            @Value("${DEFAULT_OWNER_SUB:dev-user}") String defaultOwnerSub,
            PortfolioHistoryService historyService,
            Metrics metrics,
            ObjectMapper mapper) {
        // Deserialize before RequestContext: correlationId lives inside the body, and a malformed
        // body is rejected as a 400 with no invocation context to log anyway.
        return json -> {
            PortfolioRequest request = deserialize(json, mapper);
            try (var ctx = RequestContext.begin(MODULE_LABEL, request.correlationId())) {
                ctx.withUser(request.ownerSub());
                return handle(request, store, valuation, consentGate, clock, defaultOwnerSub, historyService, metrics);
            }
        };
    }

    private static PortfolioRequest deserialize(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, PortfolioRequest.class);
        } catch (JacksonException e) {
            throw new WatchlistException("invalid request body: " + rootCauseMessage(e), e);
        }
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private static PortfolioResponse handle(
            PortfolioRequest request,
            HoldingsStoreService store,
            ValuationService valuation,
            ConsentGate consentGate,
            Clock clock,
            String defaultOwnerSub,
            PortfolioHistoryService historyService,
            Metrics metrics) {
        String owner =
                (request.ownerSub() != null && !request.ownerSub().isBlank()) ? request.ownerSub() : defaultOwnerSub;
        log.info(
                "Portfolio request. operation={} owner={} correlationId={}",
                request.operation(),
                owner,
                request.correlationId());

        // Consent gate (spec decision 5): no active consent -> no portfolio interaction at all.
        // The "consent required" prefix keeps QueryStack's CLIENT_ERROR_PATTERN 400 mapping in
        // sync; changing it here requires the matching change in QueryStack.
        if (!consentGate.isActive(owner)) {
            metrics.count("ConsentBlocked");
            throw new WatchlistException("consent required: no active consent for owner");
        }

        return switch (request.operation()) {
            case CREATE -> {
                // Spec s11 erasure step 1: refuse new holding rows while erasure is in flight.
                // Scoped to CREATE only, not the consent check above: DELETE and LIST stay allowed.
                // The "deletion pending" prefix keeps QueryStack's CLIENT_ERROR_PATTERN in sync.
                if (consentGate.isDeletionPending(owner)) {
                    throw new WatchlistException("deletion pending: erasure in progress for this account");
                }
                String ticker = TickerValidator.validate(request.ticker());
                if (request.lots() == null) {
                    throw new WatchlistException("invalid request body: holding lots must not be null");
                }
                PortfolioValidator.validateLots(request.lots(), clock);
                store.upsert(owner, ticker, request.lots());
                yield PortfolioResponse.created(ticker);
            }
            case DELETE -> {
                String ticker = TickerValidator.validate(request.ticker());
                store.delete(owner, ticker);
                yield PortfolioResponse.deleted(ticker);
            }
            case LIST -> PortfolioResponse.list(valuation.value(owner));
            case HISTORY -> PortfolioResponse.history(historyService.history(owner));
        };
    }
}
