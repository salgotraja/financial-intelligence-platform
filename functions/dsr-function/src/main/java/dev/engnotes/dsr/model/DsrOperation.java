package dev.engnotes.dsr.model;

/** DSR operation, set by the API Gateway integration template per HTTP method (GET -> EXPORT, DELETE -> ERASE). */
public enum DsrOperation {
    EXPORT,
    ERASE
}
