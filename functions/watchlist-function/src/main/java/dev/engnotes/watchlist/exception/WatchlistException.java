package dev.engnotes.watchlist.exception;

/** Thrown when a watchlist request fails validation or storage. */
public class WatchlistException extends RuntimeException {

    public WatchlistException(String message) {
        super(message);
    }

    public WatchlistException(String message, Throwable cause) {
        super(message, cause);
    }
}
