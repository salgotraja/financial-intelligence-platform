package dev.engnotes.query.service;

import java.math.BigDecimal;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Null-safe DynamoDB item-attribute readers shared by the market-data query classes ({@link
 * MarketDataQuery}, {@link DailyMarketDataQuery}): the DAY# rollup's {@code day} value with its
 * SK-derived fallback for items written before the {@code day} attribute existed, plus a
 * string/decimal/long accessor for any other attribute.
 */
final class DynamoAttributes {

    private static final String DAY_PREFIX = "DAY#";

    private DynamoAttributes() {}

    static String dayOf(Map<String, AttributeValue> item) {
        String day = attr(item, "day");
        if (day != null) {
            return day;
        }
        String sk = attr(item, "SK");
        return sk != null && sk.startsWith(DAY_PREFIX) ? sk.substring(DAY_PREFIX.length()) : sk;
    }

    static String attr(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    static BigDecimal decimal(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? null : new BigDecimal(value.n());
    }

    static Long longValue(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.n() == null ? null : Long.parseLong(value.n());
    }
}
