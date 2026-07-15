package dev.engnotes.insight.service;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Shared DynamoDB string-attribute constructor used across the correlation and group insight reads/writes. */
final class AttributeValues {

    private AttributeValues() {}

    static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
