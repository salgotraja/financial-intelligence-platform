package dev.engnotes.insight.model;

import java.math.BigDecimal;

/**
 * Pipeline input to the GenerateInsight state: the market data emitted by the
 * FetchMarketData state (a serialized MarketDataResponse). POJO with the same
 * field names the upstream response produces, so deserialization needs no
 * lenient mapper config and there are no unknown properties.
 */
public class InsightRequest {

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

    public InsightRequest() {}

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getPreviousClose() {
        return previousClose;
    }

    public void setPreviousClose(BigDecimal previousClose) {
        this.previousClose = previousClose;
    }

    public BigDecimal getChange() {
        return change;
    }

    public void setChange(BigDecimal change) {
        this.change = change;
    }

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public void setChangePercent(BigDecimal changePercent) {
        this.changePercent = changePercent;
    }

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

    public BigDecimal getMarketCap() {
        return marketCap;
    }

    public void setMarketCap(BigDecimal marketCap) {
        this.marketCap = marketCap;
    }

    public BigDecimal getHigh52Week() {
        return high52Week;
    }

    public void setHigh52Week(BigDecimal high52Week) {
        this.high52Week = high52Week;
    }

    public BigDecimal getLow52Week() {
        return low52Week;
    }

    public void setLow52Week(BigDecimal low52Week) {
        this.low52Week = low52Week;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isStored() {
        return stored;
    }

    public void setStored(boolean stored) {
        this.stored = stored;
    }
}
