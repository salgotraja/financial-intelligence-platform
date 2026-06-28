package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import dev.engnotes.dsr.model.ErasureResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

@ExtendWith(MockitoExtension.class)
class UserErasureServiceTest {

    private static final String TABLE = "financial-platform-test";

    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private CognitoUserService cognito;

    private UserErasureService erasure;

    @BeforeEach
    void setUp() {
        erasure = new UserErasureService(dynamoDb, cognito, TABLE);
    }

    @Test
    void eraseDeletesConsentWatchlistMirrorsThenCognito() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(List.of(Map.of("ticker", s("RELIANCE.NS")), Map.of("ticker", s("INFY.NS"))))
                        .build());
        when(cognito.deleteBySub("user-1")).thenReturn(true);

        ErasureResult result = erasure.erase("user-1");

        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        InOrder order = inOrder(dynamoDb, cognito);
        order.verify(dynamoDb).query(any(QueryRequest.class));
        order.verify(dynamoDb).batchWriteItem(captor.capture());
        order.verify(cognito).deleteBySub("user-1");

        List<WriteRequest> writes = captor.getValue().requestItems().get(TABLE);
        assertThat(writes)
                .extracting(w -> w.deleteRequest().key().get("PK").s() + "/"
                        + w.deleteRequest().key().get("SK").s())
                .containsExactly(
                        "USER#user-1/CONSENT",
                        "USER#user-1/WATCH#RELIANCE.NS",
                        "WATCHSET/TICKER#RELIANCE.NS",
                        "USER#user-1/WATCH#INFY.NS",
                        "WATCHSET/TICKER#INFY.NS");

        assertThat(result.status()).isEqualTo("erased");
        assertThat(result.subjectSub()).isEqualTo("user-1");
        assertThat(result.itemsDeleted()).isEqualTo(5);
        assertThat(result.cognitoUserDeleted()).isTrue();
    }

    @Test
    void eraseDeletesConsentAndCognitoEvenWhenWatchlistEmpty() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        when(cognito.deleteBySub("ghost")).thenReturn(false);

        ErasureResult result = erasure.erase("ghost");

        ArgumentCaptor<BatchWriteItemRequest> captor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        org.mockito.Mockito.verify(dynamoDb).batchWriteItem(captor.capture());
        List<WriteRequest> writes = captor.getValue().requestItems().get(TABLE);
        assertThat(writes)
                .extracting(w -> w.deleteRequest().key().get("SK").s())
                .containsExactly("CONSENT");
        assertThat(result.itemsDeleted()).isEqualTo(1);
        assertThat(result.cognitoUserDeleted()).isFalse();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
