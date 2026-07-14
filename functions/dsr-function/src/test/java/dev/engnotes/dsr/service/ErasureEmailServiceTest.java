package dev.engnotes.dsr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

@ExtendWith(MockitoExtension.class)
class ErasureEmailServiceTest {

    @Mock
    private SesV2Client ses;

    @Test
    void sendConfirmationSendsFromAlertEmailToCapturedAddress() {
        ErasureEmailService withClient = new ErasureEmailService(ses, "alerts@engnotes.dev");

        withClient.sendConfirmation("subject@example.com", "user-1", "2026-07-14T09:00:00Z");

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(captor.capture());
        SendEmailRequest request = captor.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo("alerts@engnotes.dev");
        assertThat(request.destination().toAddresses()).containsExactly("subject@example.com");
        assertThat(request.content().simple().subject().data()).contains("erased");
        assertThat(request.content().simple().body().text().data())
                .contains("user-1")
                .contains("2026-07-14T09:00:00Z");
    }

    @Test
    void sendConfirmationIsNoOpWhenAddressBlank() {
        ErasureEmailService withClient = new ErasureEmailService(ses, "alerts@engnotes.dev");

        withClient.sendConfirmation(null, "user-2", "2026-07-14T09:00:00Z");
        withClient.sendConfirmation("  ", "user-2", "2026-07-14T09:00:00Z");

        verify(ses, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void sendConfirmationPropagatesSesFailureUncaught() {
        ErasureEmailService withClient = new ErasureEmailService(ses, "alerts@engnotes.dev");
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(SdkException.create("sandbox rejection", null));

        assertThatThrownBy(() -> withClient.sendConfirmation("subject@example.com", "user-3", "2026-07-14T09:00:00Z"))
                .isInstanceOf(SdkException.class);
    }
}
