package dev.engnotes.ingestion.model;

import java.math.BigDecimal;

/**
 * Market data for a single ticker, and the pipeline's output to the next state.
 *
 * <p>Immutable record. Built via {@link #builder()} during the fetch, then refined through the
 * write path with copy-on-write helpers: {@link #withAnomaly(boolean, String)} after the anomaly
 * gate and {@link #withStored(boolean)} after persistence. Serialized to JSON by component name, so
 * the wire shape (notably {@code anomaly}, read by the Step Functions Choice) is unchanged.
 */
public record MarketDataResponse(
        String ticker,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal change,
        BigDecimal changePercent,
        Long volume,
        BigDecimal marketCap,
        BigDecimal high52Week,
        BigDecimal low52Week,
        String correlationId,
        String dataSource,
        boolean stored,
        boolean anomaly,
        String anomalyReason) {

    public static Builder builder() {
        return new Builder();
    }

    /** Returns a copy with the anomaly verdict set (after the anomaly gate). */
    public MarketDataResponse withAnomaly(boolean anomaly, String anomalyReason) {
        return new MarketDataResponse(
                ticker,
                price,
                previousClose,
                change,
                changePercent,
                volume,
                marketCap,
                high52Week,
                low52Week,
                correlationId,
                dataSource,
                stored,
                anomaly,
                anomalyReason);
    }

    /** Returns a copy with the stored flag set (after persistence). */
    public MarketDataResponse withStored(boolean stored) {
        return new MarketDataResponse(
                ticker,
                price,
                previousClose,
                change,
                changePercent,
                volume,
                marketCap,
                high52Week,
                low52Week,
                correlationId,
                dataSource,
                stored,
                anomaly,
                anomalyReason);
    }

    public static final class Builder {
        private String ticker;
        private BigDecimal price;
        private BigDecimal previousClose;
        private BigDecimal change;
        private BigDecimal changePercent;
        private Long volume;
        private BigDecimal marketCap;
        private BigDecimal high52Week;
        private BigDecimal low52Week;
        private String correlationId;
        private String dataSource;
        private boolean stored;
        private boolean anomaly;
        private String anomalyReason;

        public Builder ticker(String ticker) {
            this.ticker = ticker;
            return this;
        }

        public Builder price(BigDecimal price) {
            this.price = price;
            return this;
        }

        public Builder previousClose(BigDecimal previousClose) {
            this.previousClose = previousClose;
            return this;
        }

        public Builder change(BigDecimal change) {
            this.change = change;
            return this;
        }

        public Builder changePercent(BigDecimal changePercent) {
            this.changePercent = changePercent;
            return this;
        }

        public Builder volume(Long volume) {
            this.volume = volume;
            return this;
        }

        public Builder marketCap(BigDecimal marketCap) {
            this.marketCap = marketCap;
            return this;
        }

        public Builder high52Week(BigDecimal high52Week) {
            this.high52Week = high52Week;
            return this;
        }

        public Builder low52Week(BigDecimal low52Week) {
            this.low52Week = low52Week;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder dataSource(String dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        public Builder stored(boolean stored) {
            this.stored = stored;
            return this;
        }

        public Builder anomaly(boolean anomaly) {
            this.anomaly = anomaly;
            return this;
        }

        public Builder anomalyReason(String anomalyReason) {
            this.anomalyReason = anomalyReason;
            return this;
        }

        public MarketDataResponse build() {
            return new MarketDataResponse(
                    ticker,
                    price,
                    previousClose,
                    change,
                    changePercent,
                    volume,
                    marketCap,
                    high52Week,
                    low52Week,
                    correlationId,
                    dataSource,
                    stored,
                    anomaly,
                    anomalyReason);
        }
    }
}
