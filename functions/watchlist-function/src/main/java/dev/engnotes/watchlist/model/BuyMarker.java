package dev.engnotes.watchlist.model;

import java.math.BigDecimal;

/** A single lot purchase to annotate on the value-curve: one marker per lot, {@code price} is display-scale (2dp). */
public record BuyMarker(String day, String ticker, long qty, BigDecimal price) {}
