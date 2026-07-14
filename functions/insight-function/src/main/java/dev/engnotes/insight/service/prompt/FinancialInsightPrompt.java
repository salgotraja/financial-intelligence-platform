package dev.engnotes.insight.service.prompt;

import dev.engnotes.insight.model.CorrelationEdge;
import dev.engnotes.insight.model.GroupInsightContext;
import dev.engnotes.insight.model.InsightRequest;
import dev.engnotes.insight.model.MemberSnapshot;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Builds the Bedrock prompt for a per-ticker insight, and (Task 7) the cross-ticker variant for a
 * correlation group.
 *
 * Deterministic: the same market data always produces the same prompt string, so the
 * only variability at runtime is the model itself. Grounded: the prompt instructs the
 * model to use only the supplied numbers and to avoid speculation or financial advice.
 * The version string is persisted with each insight so a prompt change is traceable.
 *
 * The structured contract (signal/confidence/rationale/drivers) is enforced by the tool
 * schema in BedrockInsightService, not by the prose here; this prompt supplies the grounded
 * facts and tells the model to emit its judgment through that tool. The group prompt reuses the
 * same {@code emit_insight} tool and schema: {@code groupId} and {@code tickers} are context the
 * service already knows, not a model judgment, so BedrockInsightService stamps them onto the
 * response rather than asking the model to restate them.
 */
@Component
public class FinancialInsightPrompt {

    // v2: structured tool-use output (signal/confidence/rationale/drivers), supersedes the v1 free text.
    private static final String VERSION = "v2";

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
        if (data.getAnomalyReason() != null && !data.getAnomalyReason().isBlank()) {
            facts.append("Anomaly that triggered this insight: ")
                    .append(data.getAnomalyReason().strip())
                    .append('\n');
        }

        return "You are a financial analyst. Using ONLY the market data below, assess this stock's "
                + "latest move and call the emit_insight tool with: a signal (BULLISH, BEARISH, or "
                + "NEUTRAL), a confidence between 0 and 1, a concise 1 to 2 sentence rationale, and the "
                + "key drivers behind your call. Do not invent numbers, do not give financial advice, and "
                + "do not speculate beyond what the data supports.\n\nMarket data:\n"
                + facts;
    }

    private void appendNumber(StringBuilder sb, String label, BigDecimal value) {
        if (value != null) {
            sb.append(label).append(": ").append(value.toPlainString()).append('\n');
        }
    }

    /** Cross-ticker prompt for a correlation group (Task 7): the same grounding rules, group-wide. */
    public String groupPromptText(GroupInsightContext context) {
        StringBuilder facts = new StringBuilder();
        facts.append("Group: ").append(context.groupId()).append('\n');
        facts.append("Tickers: ").append(String.join(", ", context.tickers())).append('\n');
        if (context.window() != null) {
            facts.append("Correlation window: ").append(context.window()).append('\n');
        }
        if (context.anomalyReason() != null && !context.anomalyReason().isBlank()) {
            facts.append("Anomaly that triggered this insight on ")
                    .append(context.triggeringTicker())
                    .append(": ")
                    .append(context.anomalyReason().strip())
                    .append('\n');
        }
        for (MemberSnapshot member : context.members()) {
            appendMember(facts, member);
        }
        for (CorrelationEdge edge : context.pairwiseRhos()) {
            facts.append("Pairwise correlation ")
                    .append(edge.tickerA())
                    .append('-')
                    .append(edge.tickerB())
                    .append(": rho=")
                    .append(edge.rho())
                    .append('\n');
        }

        return "You are a financial analyst. Using ONLY the market data below for this correlated "
                + "group of tickers, assess the group's collective move and call the emit_insight tool "
                + "with: a signal (BULLISH, BEARISH, or NEUTRAL) for the group, a confidence between 0 "
                + "and 1, a concise 1 to 2 sentence rationale referencing the correlated movement, and "
                + "the key drivers behind your call. Do not invent numbers, do not give financial "
                + "advice, and do not speculate beyond what the data supports.\n\nGroup data:\n" + facts;
    }

    private void appendMember(StringBuilder sb, MemberSnapshot member) {
        sb.append("- ").append(member.ticker()).append(": price=");
        sb.append(member.price() == null ? "n/a" : member.price().toPlainString());
        sb.append(", changePercent=");
        sb.append(
                member.changePercent() == null ? "n/a" : member.changePercent().toPlainString());
        sb.append(", volume=").append(member.volume() == null ? "n/a" : member.volume());
        if (member.baselineVolumeMean() != null) {
            sb.append(", baselineVolumeMean=").append(member.baselineVolumeMean());
        }
        sb.append('\n');
    }
}
