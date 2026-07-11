package dev.engnotes.consent.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.engnotes.consent.model.ConsentOperation;
import dev.engnotes.consent.model.ConsentRequest;
import dev.engnotes.consent.model.ConsentResponse;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@SpringBootTest
class ConsentBeanIT extends AbstractLocalStackIT {

    @Autowired
    @Qualifier("consent")
    Function<ConsentRequest, ConsentResponse> consent;

    @Autowired
    DynamoDbClient dynamoDbClient;

    @Test
    void grantWritesConsentItemAndAuditEvent() {
        ConsentResponse response = consent.apply(
                new ConsentRequest(ConsentOperation.GRANT, "user-3", "v1", "analytics", "1.2.3.4", "corr-1"));

        assertThat(response.consentGiven()).isTrue();

        var consentItem = dynamoDbClient
                .getItem(GetItemRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s("USER#user-3").build(),
                                "SK", AttributeValue.builder().s("CONSENT").build()))
                        .build())
                .item();
        assertThat(consentItem.get("consentGiven").bool()).isTrue();

        var auditItems = dynamoDbClient
                .query(QueryRequest.builder()
                        .tableName(PlatformSchema.AUDIT_TABLE)
                        .keyConditionExpression("PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.builder().s("USER#user-3").build()))
                        .build())
                .items();
        assertThat(auditItems).isNotEmpty();
    }
}
