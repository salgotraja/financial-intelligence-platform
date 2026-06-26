package dev.engnotes.insight.service.prompt;

import dev.engnotes.insight.model.InsightRequest;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Builds the Bedrock prompt for a per-ticker insight.
 *
 * Deterministic: the same market data always produces the same prompt string, so the
 * only variability at runtime is the model itself. Grounded: the prompt instructs the
 * model to use only the supplied numbers and to avoid speculation or financial advice.
 * The version string is persisted with each insight so a prompt change is traceable.
 */
@Component
public class FinancialInsightPrompt {

    private static final String VERSION = "v1";

    public String version() {
        return VERSION;
    }

    public String promptText(InsightRequest data) {
        StringBuilder facts = new StringBuilder();
        facts.append("Ticker: ").append(data.getTicker()).append('\n');
        appendNumber(facts, "Current price", data.getPrice());
        appendNumber(facts, "Previous close", data.getPreviousClose());
        appendNumber(facts, "Change", data.getChange());
        appendNumber(facts, "Change percent", data.getChangePercent());
        appendNumber(facts, "52-week high", data.getHigh52Week());
        appendNumber(facts, "52-week low", data.getLow52Week());
        appendNumber(facts, "Market cap", data.getMarketCap());
        if (data.getVolume() != null) {
            facts.append("Volume: ").append(data.getVolume()).append('\n');
        }

        return "You are a financial analyst. Using ONLY the market data below, write a concise "
                + "2 to 3 sentence plain-English insight about this stock's latest move. State what "
                + "changed and one plausible reason it could matter. Do not invent numbers, do not give "
                + "financial advice, and do not speculate beyond what the data supports. If a field is "
                + "missing, simply omit it.\n\nMarket data:\n"
                + facts;
    }

    private void appendNumber(StringBuilder sb, String label, BigDecimal value) {
        if (value != null) {
            sb.append(label).append(": ").append(value.toPlainString()).append('\n');
        }
    }
}
