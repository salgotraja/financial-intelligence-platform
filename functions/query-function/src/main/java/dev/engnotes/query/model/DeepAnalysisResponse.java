package dev.engnotes.query.model;

import java.util.List;

/** Read-path output for the deep-analysis route: multi-horizon stats plus the 52-week band for a ticker. */
public record DeepAnalysisResponse(
        String ticker, String generatedAt, List<HorizonStats> horizons, Band52w band52w, boolean found) {

    public static DeepAnalysisResponse notFound(String ticker, String generatedAt) {
        return new DeepAnalysisResponse(ticker, generatedAt, List.of(), null, false);
    }
}
