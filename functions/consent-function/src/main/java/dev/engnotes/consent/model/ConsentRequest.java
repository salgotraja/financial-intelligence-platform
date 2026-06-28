package dev.engnotes.consent.model;

/**
 * Consent input. {@code operation} is set by the API Gateway integration template per HTTP method
 * (POST -> GRANT, GET -> VIEW, DELETE -> WITHDRAW). {@code sub} is the caller's Cognito {@code sub}
 * from the authorizer context ($context.authorizer.sub), never the body. {@code version} and
 * {@code purpose} come from the POST body (null for GET/DELETE). {@code sourceIp} is
 * $context.identity.sourceIp. {@code correlationId} is $context.requestId, used as the audit seq.
 */
public record ConsentRequest(
        ConsentOperation operation,
        String sub,
        String version,
        String purpose,
        String sourceIp,
        String correlationId) {}
