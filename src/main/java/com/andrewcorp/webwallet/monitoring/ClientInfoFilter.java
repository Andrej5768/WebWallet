package com.andrewcorp.webwallet.monitoring;

import com.andrewcorp.webwallet.common.web.ClientInfo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Determines the client of every API request once, keeps it on the request for the controllers and puts it into the
 * logging context, so that the log lines and the error alerts of a request show whether it came from the web or from
 * an app, and which version. Runs before the security chain, so that 401 and 429 responses are logged with the client
 * too.
 */
public class ClientInfoFilter extends OncePerRequestFilter {

    public static final String MDC_CLIENT = "client";
    public static final String MDC_APP_VERSION = "appVersion";
    public static final String MDC_USER_AGENT = "ua";

    private static final int MAX_USER_AGENT = 200;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ClientInfo client = ClientInfo.of(request);
        request.setAttribute(ClientInfo.REQUEST_ATTRIBUTE, client);
        MDC.put(MDC_CLIENT, client.logName());
        if (client.appVersion() != null) {
            MDC.put(MDC_APP_VERSION, client.appVersion().toString());
        }
        // The user agents of browsers are noise; those of the apps name the app, the OS and the device
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (client.nativeApp() && userAgent != null) {
            MDC.put(
                    MDC_USER_AGENT,
                    userAgent.length() > MAX_USER_AGENT ? userAgent.substring(0, MAX_USER_AGENT) : userAgent);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CLIENT);
            MDC.remove(MDC_APP_VERSION);
            MDC.remove(MDC_USER_AGENT);
        }
    }
}
