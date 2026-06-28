package dev.engnotes.dsr.model;

/** One audit-trail entry in an export, read from the audit table. */
public record AuditEventView(
        String eventType, String at, String version, String purpose, String actorSub, String sourceIp) {}
