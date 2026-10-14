package com.andrewcorp.webwallet.mail;

import com.andrewcorp.webwallet.config.AppProperties;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sends verification and password reset emails once the token is committed. A mail failure does not fail the
 * request: the user can ask for the email again. Mails are sent in the background, so that the response time does
 * not reveal whether a mail was sent, i.e. whether the account exists.
 */
@Component
public class AuthMailListener {

    private static final Logger log = LoggerFactory.getLogger(AuthMailListener.class);

    private final JavaMailSender mailSender;
    private final MessageSource messages;
    private final AppProperties properties;
    private final Executor mailExecutor;

    public AuthMailListener(
            JavaMailSender mailSender,
            MessageSource messages,
            AppProperties properties,
            @Qualifier(MailConfig.MAIL_EXECUTOR) Executor mailExecutor) {
        this.mailSender = mailSender;
        this.messages = messages;
        this.properties = properties;
        this.mailExecutor = mailExecutor;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onAuthMail(AuthMailEvent event) {
        SimpleMailMessage mail = compose(event);
        try {
            mailExecutor.execute(() -> send(mail, event));
        } catch (TaskRejectedException e) {
            log.warn("Dropped {} email to {}: the mail queue is full", event.type(), maskEmail(event.email()));
        }
    }

    private SimpleMailMessage compose(AuthMailEvent event) {
        String prefix;
        Object[] arguments;
        switch (event.type()) {
            case EMAIL_VERIFICATION -> {
                prefix = "mail.verification";
                arguments = new Object[] {
                    link("/verify-email", event.token()),
                    properties.auth().emailVerificationTtl().toHours()
                };
            }
            case PASSWORD_RESET -> {
                prefix = "mail.passwordReset";
                arguments = new Object[] {
                    link("/reset-password", event.token()),
                    properties.auth().passwordResetTtl().toMinutes()
                };
            }
            case ACCOUNT_EXISTS -> {
                prefix = "mail.accountExists";
                arguments = new Object[] {link("/forgot-password", null)};
            }
            default -> throw new IllegalArgumentException("Unknown mail type " + event.type());
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.mail().from());
        mail.setTo(event.email());
        mail.setSubject(messages.getMessage(prefix + ".subject", null, event.locale()));
        mail.setText(messages.getMessage(prefix + ".body", arguments, event.locale()));
        return mail;
    }

    /** The token goes into the fragment, which browsers send neither to the server nor in the Referer header. */
    private String link(String path, String token) {
        UriComponentsBuilder link =
                UriComponentsBuilder.fromUriString(properties.frontendUrl()).path(path);
        if (token != null) {
            link.fragment("token=" + token);
        }
        return link.toUriString();
    }

    private void send(SimpleMailMessage mail, AuthMailEvent event) {
        try {
            mailSender.send(mail);
        } catch (MailException e) {
            log.warn("Failed to send {} email to {}", event.type(), maskEmail(event.email()), e);
        }
    }

    /** Keeps the first character and the domain: {@code j***@example.com}. */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
