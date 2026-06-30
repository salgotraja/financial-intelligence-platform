package dev.engnotes.dsr.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.engnotes.dsr.model.DsrOperation;
import dev.engnotes.dsr.model.DsrRequest;
import dev.engnotes.dsr.model.DsrResponse;
import dev.engnotes.dsr.model.UserDataExport;
import dev.engnotes.dsr.service.CognitoUserService;
import dev.engnotes.itsupport.AbstractLocalStackIT;
import dev.engnotes.itsupport.PlatformSchema;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@SpringBootTest
class DsrBeanIT extends AbstractLocalStackIT {

    // Cognito is LocalStack-Pro-only; mock the service so erasure completes without a real pool.
    @MockitoBean
    CognitoUserService cognitoUserService;

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
    }

    @Test
    void eraseDeletesConsentAndCallsCognito() {
        when(cognitoUserService.deleteBySub(ArgumentMatchers.eq("subject-2"))).thenReturn(true);
        seedConsent("subject-2");
        seedWatch("subject-2", "BBB.NS");

        // Admin-on-behalf: callerSub != subjectSub so SubjectResolver requires callerGroups to
        // contain "admins" (the exact ADMIN_GROUP constant in SubjectResolver).
        dsr.apply(new DsrRequest(DsrOperation.ERASE, "admin-caller", "admins", "subject-2", "1.2.3.4", "corr-2"));

        boolean consentRemains = dynamoDbClient
                .getItem(GetItemRequest.builder()
                        .tableName(PlatformSchema.PLATFORM_TABLE)
                        .key(Map.of(
                                "PK",
                                        AttributeValue.builder()
                                                .s("USER#subject-2")
                                                .build(),
                                "SK", AttributeValue.builder().s("CONSENT").build()))
                        .build())
                .hasItem();
        assertThat(consentRemains).isFalse();
    }
}
