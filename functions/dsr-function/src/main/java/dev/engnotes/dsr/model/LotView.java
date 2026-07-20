package dev.engnotes.dsr.model;

/** Read-only export snapshot of a single portfolio lot. Values are raw strings, no numeric parsing. */
public record LotView(String buyDate, String qty, String price) {}
