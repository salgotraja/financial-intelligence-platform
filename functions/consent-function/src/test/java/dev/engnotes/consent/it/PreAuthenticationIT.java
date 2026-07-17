package dev.engnotes.consent.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@SpringBootTest
class PreAuthenticationIT extends AbstractLocalStackIT {

    @Autowired
    @Qualifier("preAuthentication")
    Function<Map<String, Object>, Map<String, Object>> preAuthentication;

    @Autowired
    DynamoDbClient dynamoDbClient;

    private Map<String, Object> cognitoEvent(String sub) {
        return Map.of(
                "userName",
                "it-user",
                "triggerSource",
                "PreAuthentication_Authentication",
                "request",
                Map.of("userAttributes", Map.of("sub", sub)),
                "response",
                Map.of());
    }

    private void seedConsent(String sub, boolean consentGiven, String version) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.builder().s("USER#" + sub).build());
        item.put("SK", AttributeValue.builder().s("CONSENT").build());
        item.put("consentGiven", AttributeValue.builder().bool(consentGiven).build());
        item.put("updatedAt", AttributeValue.builder().s("2026-07-15T05:00:00Z").build());
        if (version != null) {
            item.put("version", AttributeValue.builder().s(version).build());
        }
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(PlatformSchema.PLATFORM_TABLE)
                .item(item)
                .build());
    }

    private java.util.List<Map<String, AttributeValue>> auditRows(String sub) {
        return dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.AUDIT_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.builder().s("USER#" + sub).build()))
                        .build())
                .items();
    }

    @Test
    void pendingConsentAllowsLogin() {
        // PENDING = seeded at signup: consentGiven=false with no version yet.
        seedConsent("sub-pending", false, null);
        Map<String, Object> event = cognitoEvent("sub-pending");

        Map<String, Object> result = preAuthentication.apply(event);

        assertThat(result).isSameAs(event); // Cognito contract: the event is echoed unchanged
        assertThat(auditRows("sub-pending")).isEmpty();
    }

    @Test
    void withdrawnConsentDeniesLogin() {
        seedConsent("sub-withdrawn", false, "v1");

        assertThatThrownBy(() -> preAuthentication.apply(cognitoEvent("sub-withdrawn")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("withdrawn");
    }

    @Test
    void currentConsentAllowsLogin() {
        seedConsent("sub-current", true, "v1"); // matches CONSENT_POLICY_VERSION default

        Map<String, Object> result = preAuthentication.apply(cognitoEvent("sub-current"));

        assertThat(result).containsKey("request");
        assertThat(auditRows("sub-current")).isEmpty();
    }

    @Test
    void staleConsentDeniesAndWritesReconsentAudit() {
        seedConsent("sub-stale", true, "v0"); // stale: policy version is v1

        assertThatThrownBy(() -> preAuthentication.apply(cognitoEvent("sub-stale")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("re-consent");

        var rows = auditRows("sub-stale");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("eventType").s()).isEqualTo("CONSENT_RECONSENT_REQUIRED");
        assertThat(rows.getFirst().get("version").s()).isEqualTo("v0");
        assertThat(rows.getFirst().get("SK").s()).startsWith("EVENT#").endsWith("#login");
    }
}
