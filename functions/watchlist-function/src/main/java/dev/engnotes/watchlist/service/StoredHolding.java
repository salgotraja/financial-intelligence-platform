package dev.engnotes.watchlist.service;

import dev.engnotes.watchlist.model.Holding;
import java.time.Instant;

/**
 * A holding as persisted, carrying the store-only bookkeeping fields that sit alongside the domain
 * {@link Holding} record: {@code lastLotMutation} is {@code null} when no existing lot has ever been
 * edited or removed (fresh creation, or only additive edits so far); it drives the time-machine curve
 * floor. {@code updatedAt} is the write timestamp of the persisted item.
 */
public record StoredHolding(Holding holding, Instant lastLotMutation, Instant updatedAt) {}
