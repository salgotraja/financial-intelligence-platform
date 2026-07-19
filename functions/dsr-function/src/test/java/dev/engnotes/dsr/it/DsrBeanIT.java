package dev.engnotes.dsr.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.dsr.model.DsrOperation;
import dev.engnotes.dsr.model.DsrRequest;
import dev.engnotes.dsr.model.DsrResponse;
import dev.engnotes.dsr.model.UserDataExport;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Covers the DSR bean's EXPORT route against real DynamoDB (LocalStack). The stepwise erasure-workflow
 * tests that used to live here were deleted 2026-07-19 (Task 3): they invoked the state machine's
 * per-state operations, now removed, directly. Task 4 rewrites ERASE coverage here against the
 * collapsed synchronous cascade.
 */
@SpringBootTest
class DsrBeanIT extends AbstractLocalStackIT {

    @Autowired
    Function<DsrRequest, DsrResponse> dsr;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private void seedConsent(String sub) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("CONSENT").build(),
                        "consentGiven", AttributeValue.builder().bool(true).build(),
                        "version", AttributeValue.builder().s("v1").build(),
                        "purpose", AttributeValue.builder().s("analytics").build(),
                        "updatedAt",
                                AttributeValue.builder()
                                        .s("2026-06-30T10:00:00Z")
                                        .build()))
                .build());
    }

    private void seedWatch(String sub, String ticker) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(Map.of(
                        "PK", AttributeValue.builder().s("USER#" + sub).build(),
                        "SK", AttributeValue.builder().s("WATCH#" + ticker).build(),
                        "ticker", AttributeValue.builder().s(ticker).build()))
                .build());
    }

    @Test
    void exportAggregatesConsentAndWatchlist() {
        seedConsent("subject-1");
        seedWatch("subject-1", "AAA.NS");

        // Self-service: callerSub == subjectSub, no admin group required.
        DsrResponse response =
                dsr.apply(new DsrRequest(DsrOperation.EXPORT, "subject-1", "user", "subject-1", "1.2.3.4", "corr-1"));

        assertThat(response).isInstanceOf(UserDataExport.class);
        var export = (UserDataExport) response;
        assertThat(export.watchlist()).contains("AAA.NS");
        // ConsentView accessor is consentGiven(), not given().
        assertThat(export.consent().consentGiven()).isTrue();

        // Task 12: the hashed-subject ACCESS compliance record lands alongside the per-user
        // DATA_EXPORTED record, date-partitioned and carrying no raw sub.
        var complianceItems = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.AUDIT_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk",
                                AttributeValue.builder()
                                        .s("AUDIT#ACCESS#" + java.time.LocalDate.now(java.time.ZoneOffset.UTC))
                                        .build()))
                        .build())
                .items();
        assertThat(complianceItems).hasSize(1);
        var compliance = complianceItems.get(0);
        assertThat(compliance.get("subjectHash").s()).isNotEqualTo("subject-1");
        assertThat(compliance.get("eventType").s()).isEqualTo("ACCESS");
        assertThat(compliance).doesNotContainKey("email").doesNotContainKey("sourceIp");
        assertThat(compliance.toString()).doesNotContain("subject-1");
    }
}
