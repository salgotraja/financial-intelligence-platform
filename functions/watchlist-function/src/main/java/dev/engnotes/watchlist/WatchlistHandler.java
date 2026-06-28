package dev.engnotes.watchlist;

import dev.engnotes.watchlist.exception.WatchlistException;
import dev.engnotes.watchlist.model.WatchlistRequest;
import dev.engnotes.watchlist.model.WatchlistResponse;
import dev.engnotes.watchlist.service.ConsentGate;
import dev.engnotes.watchlist.service.WatchlistStoreService;
import dev.engnotes.watchlist.validation.TickerValidator;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Watchlist Lambda - Spring Cloud Function entry point (write path).
 *
 * <p>The bean name ("watchlist") must match SPRING_CLOUD_FUNCTION_DEFINITION in QueryStack. API
 * Gateway maps each HTTP method to this function via the integration request template, setting
 * {@code operation} (POST -> ADD, GET -> LIST, DELETE -> REMOVE), {@code ticker} from the path, and
 * {@code ownerSub} from the authorizer context ($context.authorizer.sub).
 *
 * <p>Validates the ticker at the trust boundary (spec section 12) before any write, resolves the
 * owner sub (request sub, else the DEFAULT_OWNER_SUB fallback for unauthenticated local runs), then
 * dispatches. Items are keyed under USER#{sub} so the DPDP erasure cascade stays a prefix delete.
 */
@SpringBootApplication
public class WatchlistHandler {

    private static final Logger log = LoggerFactory.getLogger(WatchlistHandler.class);

    public static void main(String[] args) {
        SpringApplication.run(WatchlistHandler.class, args);
    }

    @Bean
    public Function<WatchlistRequest, WatchlistResponse> watchlist(
            WatchlistStoreService store,
            ConsentGate consentGate,
            @Value("${DEFAULT_OWNER_SUB:dev-user}") String defaultOwnerSub) {
        return request -> {
            String owner = (request.ownerSub() != null && !request.ownerSub().isBlank())
                    ? request.ownerSub()
                    : defaultOwnerSub;
            log.info(
                    "Watchlist request. operation={} owner={} correlationId={}",
                    request.operation(),
                    owner,
                    request.correlationId());

            // Consent gate (spec decision 5): no active consent -> no watchlist interaction at all.
            if (!consentGate.isActive(owner)) {
                throw new WatchlistException("consent required: no active consent for owner");
            }

            return switch (request.operation()) {
                case ADD -> {
                    String ticker = TickerValidator.validate(request.ticker());
                    store.add(owner, ticker);
                    yield WatchlistResponse.added(ticker);
                }
                case REMOVE -> {
                    String ticker = TickerValidator.validate(request.ticker());
                    store.remove(owner, ticker);
                    yield WatchlistResponse.removed(ticker);
                }
                case LIST -> WatchlistResponse.list(store.list(owner));
            };
        };
    }
}
