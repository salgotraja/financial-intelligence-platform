package dev.engnotes.dsr.service;

import dev.engnotes.dsr.model.AuditEventView;
import dev.engnotes.dsr.model.ConsentView;
import dev.engnotes.dsr.model.HoldingView;
import dev.engnotes.dsr.model.LotView;
import dev.engnotes.dsr.model.UserDataExport;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Right-to-access reader: assembles a subject's personal data from the consent record, the watchlist
 * items ({@code PK=USER#{sub}, begins_with(SK, WATCH#)}), the portfolio holdings ({@code PK=USER#{sub},
 * begins_with(SK, HOLDING#)}), and the audit trail ({@code PK=USER#{sub}} on the audit table). Read-only;
 * the caller writes the DATA_EXPORTED audit event after a successful read.
 */
@Service
public class UserDataExportService {

    private final DynamoDbClient dynamoDb;
    private final String platformTable;
    private final String auditTable;

    public UserDataExportService(
            DynamoDbClient dynamoDb,
            @Value("${PLATFORM_TABLE:financial-platform-dev}") String platformTable,
            @Value("${AUDIT_TABLE:financial-platform-audit-dev}") String auditTable) {
        this.dynamoDb = dynamoDb;
        this.platformTable = platformTable;
        this.auditTable = auditTable;
    }

    public UserDataExport export(String subjectSub) {
        List<String> watchlist = readWatchlist(subjectSub);
        List<HoldingView> holdings = readHoldings(subjectSub);
        return new UserDataExport(
                "ok", subjectSub, readConsent(subjectSub), watchlist, readAudit(subjectSub), holdings);
    }

    private ConsentView readConsent(String subjectSub) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(platformTable)
                .key(Map.of("PK", s("USER#" + subjectSub), "SK", s("CONSENT")))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return new ConsentView(false, null, null, null);
        }
        Map<String, AttributeValue> item = response.item();
        boolean given = item.containsKey("consentGiven")
                && Boolean.TRUE.equals(item.get("consentGiven").bool());
        return new ConsentView(given, str(item, "version"), str(item, "purpose"), str(item, "updatedAt"));
    }

    private List<String> readWatchlist(String subjectSub) {
        return dynamoDb
                .queryPaginator(QueryRequest.builder()
                        .tableName(platformTable)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                        .expressionAttributeValues(Map.of(":pk", s("USER#" + subjectSub), ":sk", s("WATCH#")))
                        .build())
                .items()
                .stream()
                .map(item -> item.get("ticker"))
                .filter(Objects::nonNull)
                .map(AttributeValue::s)
                .toList();
    }

    private List<HoldingView> readHoldings(String subjectSub) {
        return dynamoDb
                .queryPaginator(QueryRequest.builder()
                        .tableName(platformTable)
                        .keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                        .expressionAttributeValues(Map.of(":pk", s("USER#" + subjectSub), ":sk", s("HOLDING#")))
                        .build())
                .items()
                .stream()
                .map(item -> new HoldingView(
                        str(item, "ticker"),
                        readLots(item),
                        rawValue(item, "totalQty"),
                        rawValue(item, "avgCost"),
                        str(item, "lastLotMutation")))
                .toList();
    }

    private static List<LotView> readLots(Map<String, AttributeValue> item) {
        AttributeValue lots = item.get("lots");
        if (lots == null || lots.l() == null) {
            return List.of();
        }
        return lots.l().stream()
                .map(AttributeValue::m)
                .map(lot -> new LotView(str(lot, "buyDate"), rawValue(lot, "qty"), rawValue(lot, "price")))
                .toList();
    }

    private List<AuditEventView> readAudit(String subjectSub) {
        return dynamoDb
                .queryPaginator(QueryRequest.builder()
                        .tableName(auditTable)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(":pk", s("USER#" + subjectSub)))
                        .build())
                .items()
                .stream()
                .map(item -> new AuditEventView(
                        str(item, "eventType"),
                        str(item, "SK"),
                        str(item, "version"),
                        str(item, "purpose"),
                        str(item, "actorSub"),
                        str(item, "sourceIp")))
                .toList();
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    /** Reads a raw string or numeric attribute's underlying value without parsing it. */
    private static String rawValue(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        if (value == null) {
            return null;
        }
        return value.s() != null ? value.s() : value.n();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
