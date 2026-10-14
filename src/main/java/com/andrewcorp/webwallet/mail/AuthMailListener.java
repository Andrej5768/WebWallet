package com.andrewcorp.webwallet.mail;

import com.andrewcorp.webwallet.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sends verification and password reset emails once the token is committed. A mail failure does not fail the
 * request: the user can ask for the email again.
 */
@Component
public class AuthMailListener {

    private static final Logger log = LoggerFactory.getLogger(AuthMailListener.class);

    private final JavaMailSender mailSender;
    private final MessageSource messages;
    private final AppProperties properties;

    public AuthMailListener(JavaMailSender mailSender, MessageSource messages, AppProperties properties) {
        this.mailSender = mailSender;
        this.messages = messages;
        this.properties = properties;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onAuthMail(AuthMailEvent event) {
        String prefix;
        String path;
        long validity;
        if (event.type() == AuthMailEvent.Type.EMAIL_VERIFICATION) {
            prefix = "mail.verification";
            path = "/verify-email";
            validity = properties.auth().emailVerificationTtl().toHours();
        } else {
            prefix = "mail.passwordReset";
            path = "/reset-password";
            validity = properties.auth().passwordResetTtl().toMinutes();
        }
        String link = UriComponentsBuilder.fromUriString(properties.frontendUrl())
                .path(path)
                .queryParam("token", event.token())
                .toUriString();

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.mail().from());
        mail.setTo(event.email());
        mail.setSubject(messages.getMessage(prefix + ".subject", null, event.locale()));
        mail.setText(messages.getMessage(
                prefix + ".body", new Object[] {event.firstName(), link, validity}, event.locale()));
        try {
            mailSender.send(mail);
        } catch (MailException e) {
            log.warn("Failed to send {} email to {}", event.type(), event.email(), e);
        }
    }
}
