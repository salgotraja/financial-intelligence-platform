package dev.engnotes.watchlist.service;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * Consent gate for watchlist operations (spec decision 5). Reads the authoritative consent record
 * {@code USER#{sub}/CONSENT} directly (decision (a): a single strongly-consistent GetItem with the
 * known key, no shared module). Strong consistency matters because withdrawal flips the record
 * immediately before purging, and a concurrent ADD must observe the flip. Fails closed: an absent
 * record, a false flag, or any read error denies.
 *
 * <p>{@link #isDeletionPending} is a second, separate GetItem against {@code USER#{sub}/PROFILE}
 * (spec s11 erasure step 1), called only on the watchlist add path. Folding it into {@link
 * #isActive} would double the read cost of every list/remove call for a check those paths do not
 * need; a second GetItem scoped to add keeps the cost where the requirement is.
 */
@Service
public class ConsentGate {

    private static final Logger log = LoggerFactory.getLogger(ConsentGate.class);

    private final DynamoDbClient dynamoDb;
    private final String platformTable;

    public ConsentGate(
            DynamoDbClient dynamoDb, @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
    }

    public boolean isActive(String sub) {
        try {
            GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(platformTable)
                    .key(Map.of("PK", s("USER#" + sub), "SK", s("CONSENT")))
                    .consistentRead(true)
                    .build());
            return response.hasItem()
                    && response.item().containsKey("consentGiven")
                    && Boolean.TRUE.equals(response.item().get("consentGiven").bool());
        } catch (RuntimeException e) {
            log.warn("Consent read failed; denying (fail-closed). sub={} error={}", sub, e.getMessage());
            return false;
        }
    }

    /** Fails closed: any read error is treated as pending, denying the write. */
    public boolean isDeletionPending(String sub) {
        try {
            GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(platformTable)
                    .key(Map.of("PK", s("USER#" + sub), "SK", s("PROFILE")))
                    .consistentRead(true)
                    .build());
            return response.hasItem()
                    && response.item().containsKey("deletionPending")
                    && Boolean.TRUE.equals(
                            response.item().get("deletionPending").bool());
        } catch (RuntimeException e) {
            log.warn("Deletion-pending read failed; denying (fail-closed). sub={} error={}", sub, e.getMessage());
            return true;
        }
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
