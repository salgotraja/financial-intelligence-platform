package dev.engnotes.dsr.service;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Shared DynamoDB string-attribute constructor used across the DSR write paths. */
final class AttributeValues {

    private AttributeValues() {}

    static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
