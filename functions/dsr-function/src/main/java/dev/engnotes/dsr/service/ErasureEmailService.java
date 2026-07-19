package dev.engnotes.dsr.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Sends the erasure confirmation email (spec s11, Task 11) via SES v2. Sender is {@code ALERT_EMAIL},
 * the same address the platform's SNS alarm topics already use (DataStack's {@code EmailIdentity},
 * verified once). SES sandbox note: in dev/sandbox mode the recipient must also be a verified
 * identity, so this effectively sends to the operator's own inbox until the account requests
 * production access. Content is plain and factual: no PII beyond the recipient address itself.
 *
 * <p>Any SES failure (including sandbox rejection of an unverified recipient) propagates as an
 * unchecked exception, deliberately not caught here: {@link ErasureService#erase} sends this
 * confirmation inline, after the Cognito delete, using the email it captured at the start of the
 * cascade, and it is the one that catches a send failure and folds it to {@code emailSent=false}
 * without failing the erasure.
 */
@Service
public class ErasureEmailService {

    private static final Logger log = LoggerFactory.getLogger(ErasureEmailService.class);

    private final SesV2Client ses;
    private final String senderEmail;

    public ErasureEmailService(SesV2Client ses, @Value("${ALERT_EMAIL:alerts@engnotes.dev}") String senderEmail) {
        this.ses = ses;
        this.senderEmail = senderEmail;
    }

    /** No-op when {@code toEmail} is blank (no email was captured before the Cognito identity was deleted). */
    public void sendConfirmation(String toEmail, String subjectSub, String requestedAt) {
        if (toEmail == null || toEmail.isBlank()) {
            log.info("Erasure confirmation email skipped (no address captured). subjectSub={}", subjectSub);
            return;
        }
        String body = "Your account and personal data (subject " + subjectSub + ") were erased.\n"
                + "Erasure requested at: " + requestedAt + "\n"
                + "This is an automated confirmation; no action is required.";
        ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(senderEmail)
                .destination(Destination.builder().toAddresses(toEmail).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder()
                                        .data("Your account has been erased")
                                        .build())
                                .body(Body.builder()
                                        .text(Content.builder().data(body).build())
                                        .build())
                                .build())
                        .build())
                .build());
        log.info("Sent erasure confirmation email. subjectSub={}", subjectSub);
    }
}
