package dev.engnotes.dsr.model;

/**
 * DSR operation. Values set by the API Gateway integration template per HTTP method (GET -> EXPORT,
 * DELETE -> ERASE); workflow discriminators removed 2026-07-19.
 */
public enum DsrOperation {
    EXPORT,
    ERASE
}
