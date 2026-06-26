package dev.engnotes.insight.model;

import java.util.List;

/**
 * The generated insight for a single ticker, and the GenerateInsight state's output.
 * Built via {@link #builder()}; the mutable {@code stored} flag is flipped to true by
 * InsightStoreService after persistence. Serialized to JSON via its getters.
 *
 * <p>Carries the structured contract (signal/confidence/rationale/drivers, spec section 9) plus
 * {@code source} (BEDROCK or RULE_BASED) so consumers know whether the insight came from the model
 * or the deterministic fallback. {@code insightText} mirrors the rationale for the read path.
 */
public class InsightResponse {

    private String ticker;
    private String generatedAt;
    private String insightText;
    private String modelId;
    private String promptVersion;
    private String correlationId;
    private boolean stored;
    private String signal;
    private double confidence;
    private String rationale;
    private List<String> drivers;
    private String source;

    public InsightResponse() {}

    private InsightResponse(Builder b) {
        this.ticker = b.ticker;
        this.generatedAt = b.generatedAt;
        this.insightText = b.insightText;
        this.modelId = b.modelId;
        this.promptVersion = b.promptVersion;
        this.correlationId = b.correlationId;
        this.stored = b.stored;
        this.signal = b.signal;
        this.confidence = b.confidence;
        this.rationale = b.rationale;
        this.drivers = b.drivers;
        this.source = b.source;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTicker() {
        return ticker;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public String getInsightText() {
        return insightText;
    }

    public String getModelId() {
        return modelId;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public boolean isStored() {
        return stored;
    }

    public void setStored(boolean stored) {
        this.stored = stored;
    }

    public String getSignal() {
        return signal;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getRationale() {
        return rationale;
    }

    public List<String> getDrivers() {
        return drivers;
    }

    public String getSource() {
        return source;
    }

    public static final class Builder {
        private String ticker;
        private String generatedAt;
        private String insightText;
        private String modelId;
        private String promptVersion;
        private String correlationId;
        private boolean stored;
        private String signal;
        private double confidence;
        private String rationale;
        private List<String> drivers;
        private String source;

        public Builder ticker(String ticker) {
            this.ticker = ticker;
            return this;
        }

        public Builder generatedAt(String generatedAt) {
            this.generatedAt = generatedAt;
            return this;
        }

        public Builder insightText(String insightText) {
            this.insightText = insightText;
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder promptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder stored(boolean stored) {
            this.stored = stored;
            return this;
        }

        public Builder signal(String signal) {
            this.signal = signal;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder rationale(String rationale) {
            this.rationale = rationale;
            return this;
        }

        public Builder drivers(List<String> drivers) {
            this.drivers = drivers;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public InsightResponse build() {
            return new InsightResponse(this);
        }
    }
}
