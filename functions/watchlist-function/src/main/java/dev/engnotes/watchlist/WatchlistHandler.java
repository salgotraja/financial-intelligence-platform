package dev.engnotes.watchlist;

import dev.engnotes.watchlist.model.WatchlistRequest;
import dev.engnotes.watchlist.model.WatchlistResponse;
import dev.engnotes.watchlist.service.WatchlistStoreService;
import dev.engnotes.watchlist.validation.TickerValidator;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Watchlist Lambda - Spring Cloud Function entry point (write path).
 *
 * <p>The bean name ("watchlist") must match SPRING_CLOUD_FUNCTION_DEFINITION in QueryStack. API
 * Gateway maps each HTTP method to this function via the integration request template, setting
 * {@code operation} (POST -> ADD, GET -> LIST, DELETE -> REMOVE) and {@code ticker} from the path.
 *
 * <p>Validates the ticker at the trust boundary (spec section 12) before any write, then dispatches
 * on the operation. Items are keyed under USER#{sub} so the future DPDP erasure cascade stays a
 * single prefix delete (spec section 11).
 */
@SpringBootApplication
public class WatchlistHandler {

    private static final Logger log = LoggerFactory.getLogger(WatchlistHandler.class);

    public static void main(String[] args) {
        SpringApplication.run(WatchlistHandler.class, args);
    }

    @Bean
    public Function<WatchlistRequest, WatchlistResponse> watchlist(WatchlistStoreService store) {
        return request -> {
            String correlationId = request.correlationId();
            log.info("Watchlist request. operation={} correlationId={}", request.operation(), correlationId);

            return switch (request.operation()) {
                case ADD -> {
                    String ticker = TickerValidator.validate(request.ticker());
                    store.add(ticker);
                    yield WatchlistResponse.added(ticker);
                }
                case REMOVE -> {
                    String ticker = TickerValidator.validate(request.ticker());
                    store.remove(ticker);
                    yield WatchlistResponse.removed(ticker);
                }
                case LIST -> WatchlistResponse.list(store.list());
            };
        };
    }
}
