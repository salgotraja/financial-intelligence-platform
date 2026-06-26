package dev.engnotes.ingestion.exception;

public class MarketDataException extends RuntimeException {
    public MarketDataException(String message) {
        super(message);
    }

    public MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
