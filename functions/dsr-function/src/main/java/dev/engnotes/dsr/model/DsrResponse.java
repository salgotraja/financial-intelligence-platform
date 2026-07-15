package dev.engnotes.dsr.model;

/**
 * Closed result set for the single {@code dsr} bean: a data export, the legacy synchronous erasure
 * outcome ({@link ErasureResult}, no longer produced by this bean but kept for
 * {@link dev.engnotes.dsr.service.UserErasureService#erase}), the erasure workflow's start
 * acknowledgement ({@link ErasureAcceptance}), or one of its per-state results ({@link ErasureStepResult}).
 */
public sealed interface DsrResponse permits UserDataExport, ErasureResult, ErasureAcceptance, ErasureStepResult {}
