package dev.engnotes.watchlist;

import dev.engnotes.watchlist.exception.WatchlistException;
import dev.engnotes.watchlist.model.PortfolioRequest;
import dev.engnotes.watchlist.model.PortfolioResponse;
import dev.engnotes.watchlist.portfolio.PortfolioValidator;
import dev.engnotes.watchlist.service.ConsentGate;
import dev.engnotes.watchlist.service.HoldingsStoreService;
import dev.engnotes.watchlist.service.ValuationService;
import dev.engnotes.watchlist.validation.TickerValidator;
import java.time.Clock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Portfolio Lambda - Spring Cloud Function entry point (write path). Component-scanned by {@link
 * WatchlistHandler}; deployed as its own Lambda from the same jar.
 *
 * <p>The bean name ("portfolio") must match SPRING_CLOUD_FUNCTION_DEFINITION in QueryStack for the
 * portfolio Lambda. API Gateway maps each HTTP method to this function via the integration request
 * template, setting {@code operation} (POST -> CREATE, GET -> LIST, DELETE -> DELETE), {@code
 * ticker} from the path, {@code lots} from the body (CREATE only), and {@code ownerSub} from the
 * authorizer context ($context.authorizer.sub).
 *
 * <p>Validates the ticker at the trust boundary (spec section 12) before any write, resolves the
 * owner sub (request sub, else the DEFAULT_OWNER_SUB fallback for unauthenticated local runs), then
 * dispatches. Items are keyed under {@code USER#{sub}}/{@code HOLDING#{ticker}} so the DPDP erasure
 * cascade stays a prefix delete.
 */
@Configuration
public class PortfolioHandler {

    private static final Logger log = LoggerFactory.getLogger(PortfolioHandler.class);

    @Bean
    public Function<PortfolioRequest, PortfolioResponse> portfolio(
            HoldingsStoreService store,
            ValuationService valuation,
            ConsentGate consentGate,
            Clock clock,
            @Value("${DEFAULT_OWNER_SUB:dev-user}") String defaultOwnerSub) {
        return request -> {
            String owner = (request.ownerSub() != null && !request.ownerSub().isBlank())
                    ? request.ownerSub()
                    : defaultOwnerSub;
            log.info(
                    "Portfolio request. operation={} owner={} correlationId={}",
                    request.operation(),
                    owner,
                    request.correlationId());

            // Consent gate (spec decision 5): no active consent -> no portfolio interaction at all.
            // The "consent required" prefix keeps QueryStack's CLIENT_ERROR_PATTERN 400 mapping in
            // sync; changing it here requires the matching change in QueryStack.
            if (!consentGate.isActive(owner)) {
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
                        throw new WatchlistException("Holding lots must not be null");
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
            };
        };
    }
}
