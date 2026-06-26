package dev.engnotes.ingestion.model;

/**
 * Pipeline input: the ticker to fetch and the correlation id for tracing.
 * Shaped by the ValidateInput Pass state in the Step Functions workflow.
 * POJO (not a record) so Jackson can deserialize the Lambda event payload.
 */
public class MarketDataRequest {

    private String ticker;
    private String correlationId;

    public MarketDataRequest() {}

    public MarketDataRequest(String ticker, String correlationId) {
        this.ticker = ticker;
        this.correlationId = correlationId;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
