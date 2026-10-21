package com.andrewcorp.webwallet.monitoring;

import com.andrewcorp.webwallet.auth.AuthRateLimiter;
import com.andrewcorp.webwallet.common.util.AttemptCounter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits the requests to the API per client address, so that one client cannot overload the server. The auth endpoints
 * have stricter limits of their own; the Monobank and Telegram webhooks are left out, since they come from the
 * addresses of the services, shared by all users.
 */
public class RequestRateLimitFilter extends OncePerRequestFilter {

    /** Writes the problem response when the limit is reached. */
    @FunctionalInterface
    public interface Rejection {
        void reject(HttpServletRequest request, HttpServletResponse response) throws IOException;
    }

    private final AttemptCounter requests;
    private final Rejection rejection;

    public RequestRateLimitFilter(int requestsPerMinute, Rejection rejection) {
        this.requests = new AttemptCounter(requestsPerMinute, Duration.ofMinutes(1));
        this.rejection = rejection;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.startsWith("/api/v1/banks/monobank/webhook/")
                || path.equals("/api/v1/telegram/webhook");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = AuthRateLimiter.clientKey(request.getRemoteAddr());
        if (requests.isExceeded(client)) {
            rejection.reject(request, response);
            return;
        }
        requests.record(client);
        chain.doFilter(request, response);
    }

    /** Forgets all requests. */
    public void reset() {
        requests.resetAll();
    }
}
