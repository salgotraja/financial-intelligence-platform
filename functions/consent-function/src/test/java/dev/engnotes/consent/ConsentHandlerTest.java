package dev.engnotes.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.CognitoUserPoolPostConfirmationEvent;
import dev.engnotes.consent.model.ConsentOperation;
import dev.engnotes.consent.model.ConsentRecord;
import dev.engnotes.consent.model.ConsentRequest;
import dev.engnotes.consent.model.ConsentResponse;
import dev.engnotes.consent.service.ConsentStoreService;
import dev.engnotes.consent.service.UserWatchlistPurgeService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsentHandlerTest {

    private static final String DEFAULT_SUB = "dev-user";

    @Mock
    private ConsentStoreService store;

    @Mock
    private UserWatchlistPurgeService purge;

    @Test
    void grantUsesContextSubAndReturnsGranted() {
        when(store.grant("user-123", "v1", "market", "1.2.3.4", "corr-1"))
                .thenReturn(new ConsentRecord(true, "v1", "market", "2026-06-28T00:00:00Z"));

        ConsentResponse response = new ConsentHandler()
                .consent(store, purge, DEFAULT_SUB)
                .apply(new ConsentRequest(ConsentOperation.GRANT, "user-123", "v1", "market", "1.2.3.4", "corr-1"));

        verify(store).grant("user-123", "v1", "market", "1.2.3.4", "corr-1");
        assertThat(response.status()).isEqualTo("granted");
        assertThat(response.consentGiven()).isTrue();
    }

    @Test
    void viewReturnsCurrentRecord() {
        when(store.read("user-123")).thenReturn(new ConsentRecord(true, "v1", "market", "ts"));

        ConsentResponse response = new ConsentHandler()
                .consent(store, purge, DEFAULT_SUB)
                .apply(new ConsentRequest(ConsentOperation.VIEW, "user-123", null, null, null, "corr-2"));

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.consentGiven()).isTrue();
    }

    @Test
    void withdrawFlipsThenPurgesInOrder() {
        when(store.withdraw("user-123", "9.9.9.9", "corr-3"))
                .thenReturn(new ConsentRecord(false, "v1", "market", "ts"));

        ConsentResponse response = new ConsentHandler()
                .consent(store, purge, DEFAULT_SUB)
                .apply(new ConsentRequest(ConsentOperation.WITHDRAW, "user-123", null, null, "9.9.9.9", "corr-3"));

        InOrder order = inOrder(store, purge);
        order.verify(store).withdraw("user-123", "9.9.9.9", "corr-3");
        order.verify(purge).purge("user-123");
        assertThat(response.status()).isEqualTo("withdrawn");
        assertThat(response.consentGiven()).isFalse();
    }

    @Test
    void fallsBackToDefaultSubWhenContextSubBlank() {
        when(store.read(DEFAULT_SUB)).thenReturn(ConsentRecord.deny());

        new ConsentHandler()
                .consent(store, purge, DEFAULT_SUB)
                .apply(new ConsentRequest(ConsentOperation.VIEW, null, null, null, null, "corr-4"));

        verify(store).read(DEFAULT_SUB);
    }

    @Test
    void postConfirmationSeedsDefaultDenyAndReturnsEventUnchanged() {
        CognitoUserPoolPostConfirmationEvent.Request request = CognitoUserPoolPostConfirmationEvent.Request.builder()
                .withUserAttributes(Map.of("sub", "user-999", "email", "a@b.com"))
                .build();
        CognitoUserPoolPostConfirmationEvent event = CognitoUserPoolPostConfirmationEvent.builder()
                .withUserName("a@b.com")
                .withRequest(request)
                .build();

        CognitoUserPoolPostConfirmationEvent result =
                new ConsentHandler().postConfirmation(store).apply(event);

        verify(store).seedDefaultDeny("user-999");
        assertThat(result).isSameAs(event);
    }
}
