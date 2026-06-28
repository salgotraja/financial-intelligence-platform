package dev.engnotes.dsr.model;

/** Closed result set for the single {@code dsr} bean: an export or an erasure outcome. */
public sealed interface DsrResponse permits UserDataExport, ErasureResult {}
