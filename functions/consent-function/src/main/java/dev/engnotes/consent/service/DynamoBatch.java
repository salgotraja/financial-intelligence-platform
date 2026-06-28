package dev.engnotes.consent.service;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * Writes all requests via BatchWriteItem in chunks of 25, draining UnprocessedItems (which DynamoDB
 * returns under throttling, independent of item count) with bounded exponential backoff. Throws if
 * items remain unprocessed after MAX_ATTEMPTS. Duplicated per-module by design (independent-module
 * pattern); the copy in dsr-function is byte-identical except the package line.
 */
final class DynamoBatch {

    private static final Logger log = LoggerFactory.getLogger(DynamoBatch.class);
    private static final int CHUNK = 25;
    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MILLIS = 25L;

    private DynamoBatch() {}

    static void batchWriteAllWithRetry(DynamoDbClient dynamoDb, String table, List<WriteRequest> writes) {
        for (int i = 0; i < writes.size(); i += CHUNK) {
            List<WriteRequest> chunk = writes.subList(i, Math.min(i + CHUNK, writes.size()));
            writeChunkWithRetry(dynamoDb, table, chunk);
        }
    }

    private static void writeChunkWithRetry(DynamoDbClient dynamoDb, String table, List<WriteRequest> chunk) {
        Map<String, List<WriteRequest>> requestItems = Map.of(table, chunk);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            BatchWriteItemResponse response = dynamoDb.batchWriteItem(
                    BatchWriteItemRequest.builder().requestItems(requestItems).build());
            Map<String, List<WriteRequest>> unprocessed = response.unprocessedItems();
            if (unprocessed == null || unprocessed.isEmpty()) {
                return;
            }
            requestItems = unprocessed;
            if (attempt < MAX_ATTEMPTS - 1) {
                sleep(BASE_BACKOFF_MILLIS << attempt);
            }
        }
        throw new IllegalStateException("BatchWriteItem left unprocessed items after " + MAX_ATTEMPTS + " attempts");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during BatchWriteItem backoff", e);
        }
    }
}
