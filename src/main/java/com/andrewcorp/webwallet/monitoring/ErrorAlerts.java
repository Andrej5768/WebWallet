package com.andrewcorp.webwallet.monitoring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.mail.MailConfig;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Emails the errors the application logs: unhandled exceptions of requests, failures of background tasks. The first
 * error goes out at once; the following ones within {@link #QUIET_PERIOD} are counted and summed up in one email when
 * it ends, so that a burst of errors does not flood the mailbox. Off while {@code app.alerts.email} is empty.
 */
@Component
public class ErrorAlerts {

    static final Duration QUIET_PERIOD = Duration.ofMinutes(15);
    private static final int MAX_TRACE = 6000;
    private static final Logger log = LoggerFactory.getLogger(ErrorAlerts.class);

    private final String recipient;
    private final JavaMailSender mailSender;
    private final Executor mailExecutor;
    private final String from;
    private final String instance;
    private final Clock clock;

    private AppenderBase<ILoggingEvent> appender;
    private Instant lastSentAt;
    private int suppressed;
    private String lastSuppressed;

    public ErrorAlerts(
            @Value("${app.alerts.email:}") String recipient,
            JavaMailSender mailSender,
            @Qualifier(MailConfig.MAIL_EXECUTOR) Executor mailExecutor,
            AppProperties properties,
            Clock clock) {
        this.recipient = recipient;
        this.mailSender = mailSender;
        this.mailExecutor = mailExecutor;
        this.from = properties.mail().from();
        this.instance = URI.create(properties.frontendUrl()).getHost();
        this.clock = clock;
    }

    public boolean enabled() {
        return !recipient.isBlank();
    }

    /** Attaches to the root logger once the application runs, so that every logged error is reported. */
    @EventListener(ApplicationReadyEvent.class)
    public void attach() {
        if (!enabled() || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                // Errors of sending an alert are logged here too; they must not cause another alert
                if (event.getLevel().isGreaterOrEqual(Level.ERROR)
                        && !event.getLoggerName().equals(log.getName())) {
                    report(event.getLoggerName(), withClient(event), trace(event));
                }
            }
        };
        appender.setName("errorAlerts");
        appender.setContext(context);
        appender.start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    /** Detaches from the root logger, which outlives the application context. */
    @PreDestroy
    public void detach() {
        if (appender != null && LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            appender.stop();
        }
    }

    /** Emails the error now, or counts it when an alert went out within the quiet period. */
    public synchronized void report(String source, String message, String trace) {
        Instant now = clock.instant();
        if (lastSentAt != null && lastSentAt.plus(QUIET_PERIOD).isAfter(now)) {
            suppressed++;
            lastSuppressed = source + ": " + message;
            return;
        }
        lastSentAt = now;
        send(
                "[WebWallet " + instance + "] " + firstLine(message),
                "An error happened at " + now + " in " + source + ".\n\n" + message + "\n\n" + trace);
    }

    /** Sums up the errors held back during the quiet period. */
    @Scheduled(fixedDelay = 15, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public synchronized void flush() {
        Instant now = clock.instant();
        if (suppressed == 0 || lastSentAt.plus(QUIET_PERIOD).isAfter(now)) {
            return;
        }
        send(
                "[WebWallet " + instance + "] " + suppressed + " more errors",
                suppressed + " more errors happened after the last alert. The last one:\n\n" + lastSuppressed
                        + "\n\nSee the logs of the backend for all of them.");
        suppressed = 0;
        lastSuppressed = null;
        lastSentAt = now;
    }

    private void send(String subject, String text) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(recipient);
        mail.setSubject(subject.length() > 150 ? subject.substring(0, 150) : subject);
        mail.setText(text);
        try {
            mailExecutor.execute(() -> {
                try {
                    mailSender.send(mail);
                } catch (MailException e) {
                    log.warn("Failed to send an error alert: {}", e.getMessage());
                }
            });
        } catch (TaskRejectedException e) {
            log.warn("Dropped an error alert: the mail queue is full");
        }
    }

    /** The message with the app that made the request in front, so that app problems are told apart at a glance. */
    private static String withClient(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        String client = mdc.get(ClientInfoFilter.MDC_CLIENT);
        if (client == null || client.equals("web")) {
            return event.getFormattedMessage();
        }
        return "[" + client + " " + mdc.getOrDefault(ClientInfoFilter.MDC_APP_VERSION, "?") + "] "
                + event.getFormattedMessage();
    }

    private static String trace(ILoggingEvent event) {
        if (event.getThrowableProxy() == null) {
            return "";
        }
        String trace = ThrowableProxyUtil.asString(event.getThrowableProxy());
        return trace.length() > MAX_TRACE ? trace.substring(0, MAX_TRACE) + "\n..." : trace;
    }

    private static String firstLine(String message) {
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }
}
