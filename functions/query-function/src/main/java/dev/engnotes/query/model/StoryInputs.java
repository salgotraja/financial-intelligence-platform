package dev.engnotes.query.model;

/**
 * How much data the sibling {@link StoryResponse}'s narrative is based on: the number of daily
 * rollups considered (may be fewer than the 7-day window requested when history is sparse) and
 * whether a latest insight was available (0 or 1).
 */
public record StoryInputs(int days, int insightCount) {}
