package com.andrewcorp.webwallet.demo;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * What a demo sandbox cannot do: reach outside. Connecting a bank or Telegram, linking Google and exporting the
 * data stay for real accounts; everything inside the wallet works. The paths are matched here rather than in each
 * service, so that the list is in one place.
 */
@Component
public class DemoRestrictions implements HandlerInterceptor {

    /** Method and path, as the request names them. */
    private static final Set<String> RESTRICTED = Set.of(
            "POST /api/v1/banks/monobank",
            "POST /api/v1/telegram/link",
            "POST /api/v1/me/google/link",
            "GET /api/v1/me/export",
            "POST /api/v1/me/delete",
            "POST /api/v1/me/password");

    private final UserRepository users;

    public DemoRestrictions(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!RESTRICTED.contains(request.getMethod() + " " + request.getRequestURI())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            Long userId = Long.valueOf(jwt.getSubject());
            if (users.findById(userId).map(user -> user.isDemo()).orElse(false)) {
                throw new ApiException(ErrorCode.DEMO_RESTRICTED);
            }
        }
        return true;
    }
}
