package dev.engnotes.dsr.model;

/** Consent slice of an export, mirroring the USER#{sub}/CONSENT record. */
public record ConsentView(boolean consentGiven, String version, String purpose, String updatedAt) {}
