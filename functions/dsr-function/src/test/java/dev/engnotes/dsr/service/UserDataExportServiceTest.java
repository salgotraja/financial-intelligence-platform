package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.engnotes.dsr.model.UserDataExport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

@ExtendWith(MockitoExtension.class)
class UserDataExportServiceTest {

    private static final String PLATFORM_TABLE = "financial-platform-test";
    private static final String AUDIT_TABLE = "financial-platform-audit-test";

    @Mock
    private DynamoDbClient dynamoDb;

    private UserDataExportService export;

    @BeforeEach
    void setUp() {
        export = new UserDataExportService(dynamoDb, PLATFORM_TABLE, AUDIT_TABLE);
    }

    @Test
    void exportAssemblesConsentWatchlistAndAudit() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(Map.of(
                                "consentGiven",
                                        AttributeValue.builder().bool(true).build(),
                                "version", s("v1"),
                                "purpose", s("market"),
                                "updatedAt", s("2026-06-28T00:00:00Z")))
                        .build());
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(
                        QueryResponse.builder()
                                .items(List.of(Map.of("ticker", s("RELIANCE.NS")), Map.of("ticker", s("INFY.NS"))))
                                .build(),
                        QueryResponse.builder()
                                .items(List.of(Map.of(
                                        "eventType", s("CONSENT_GRANTED"),
                                        "SK", s("EVENT#2026-06-28T00:00:00Z#corr-1"),
                                        "version", s("v1"),
                                        "purpose", s("market"),
                                        "actorSub", s("user-1"),
                                        "sourceIp", s("1.2.3.4"))))
                                .build());
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));

        UserDataExport result = export.export("user-1");

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.subjectSub()).isEqualTo("user-1");
        assertThat(result.consent().consentGiven()).isTrue();
        assertThat(result.consent().version()).isEqualTo("v1");
        assertThat(result.watchlist()).containsExactly("RELIANCE.NS", "INFY.NS");
        assertThat(result.auditTrail()).hasSize(1);
        assertThat(result.auditTrail().getFirst().eventType()).isEqualTo("CONSENT_GRANTED");
        assertThat(result.auditTrail().getFirst().at()).isEqualTo("EVENT#2026-06-28T00:00:00Z#corr-1");
    }

    @Test
    void exportYieldsEmptyShapesWhenNoData() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));

        UserDataExport result = export.export("ghost");

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.consent().consentGiven()).isFalse();
        assertThat(result.consent().version()).isNull();
        assertThat(result.watchlist()).isEmpty();
        assertThat(result.auditTrail()).isEmpty();
    }

    @Test
    void exportPaginatesAuditTrailAcrossPages() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(inv -> new QueryIterable(dynamoDb, inv.getArgument(0)));
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build()) // watchlist: one empty page
                .thenReturn(QueryResponse.builder() // audit page 1
                        .items(List.of(Map.of("eventType", s("CONSENT_GRANTED"), "SK", s("EVENT#1"))))
                        .lastEvaluatedKey(Map.of("PK", s("USER#user-1"), "SK", s("EVENT#1")))
                        .build())
                .thenReturn(QueryResponse.builder() // audit page 2
                        .items(List.of(Map.of("eventType", s("DATA_EXPORTED"), "SK", s("EVENT#2"))))
                        .build());

        UserDataExport result = export.export("user-1");

        assertThat(result.auditTrail()).hasSize(2);
        assertThat(result.auditTrail())
                .extracting(a -> a.eventType())
                .containsExactly("CONSENT_GRANTED", "DATA_EXPORTED");
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
