package com.andrewcorp.webwallet.common.error;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds localized ProblemDetail bodies shared by the MVC exception handler and the security filters.
 */
@Component
public class ProblemDetails {

    public static final String CODE_PROPERTY = "code";

    private final MessageSource messages;

    public ProblemDetails(MessageSource messages) {
        this.messages = messages;
    }

    public ProblemDetail of(ErrorCode code) {
        return of(code, LocaleContextHolder.getLocale());
    }

    public ProblemDetail of(ErrorCode code, Locale locale) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(code.status(), messages.getMessage(code.messageKey(), null, locale));
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty(CODE_PROPERTY, code.name());
        return problem;
    }
}
