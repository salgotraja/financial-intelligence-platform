package dev.engnotes.ingestion.model;

import java.math.BigDecimal;

/**
 * Market data for a single ticker, and the pipeline's output to the next state.
 * Built via {@link #builder()}; the mutable {@code stored} flag is flipped to true
 * by MarketDataStoreService after persistence. Serialized to JSON via its getters.
 */
public class MarketDataResponse {

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

    public MarketDataResponse() {}

    private MarketDataResponse(Builder b) {
        this.ticker = b.ticker;
        this.price = b.price;
        this.previousClose = b.previousClose;
        this.change = b.change;
        this.changePercent = b.changePercent;
        this.volume = b.volume;
        this.marketCap = b.marketCap;
        this.high52Week = b.high52Week;
        this.low52Week = b.low52Week;
        this.correlationId = b.correlationId;
        this.dataSource = b.dataSource;
        this.stored = b.stored;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTicker() {
        return ticker;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getPreviousClose() {
        return previousClose;
    }

    public BigDecimal getChange() {
        return change;
    }

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public Long getVolume() {
        return volume;
    }

    public BigDecimal getMarketCap() {
        return marketCap;
    }

    public BigDecimal getHigh52Week() {
        return high52Week;
    }

    public BigDecimal getLow52Week() {
        return low52Week;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getDataSource() {
        return dataSource;
    }

    public boolean isStored() {
        return stored;
    }

    public void setStored(boolean stored) {
        this.stored = stored;
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

        public MarketDataResponse build() {
            return new MarketDataResponse(this);
        }
    }
}
