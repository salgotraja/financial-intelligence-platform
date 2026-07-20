package dev.engnotes.dsr.model;

import java.util.List;

/** Read-only export snapshot of a subject's {@code HOLDING#{ticker}} item. */
public record HoldingView(String ticker, List<LotView> lots, String totalQty, String avgCost, String lastLotMutation) {}
