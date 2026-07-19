package dev.engnotes.dsr.model;

/**
 * Closed result set for the single {@code dsr} bean: a data export or the synchronous erasure outcome.
 */
public sealed interface DsrResponse permits UserDataExport, ErasureResult {}
