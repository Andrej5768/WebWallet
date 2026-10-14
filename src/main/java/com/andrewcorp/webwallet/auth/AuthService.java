package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.dto.LoginRequest;
import com.andrewcorp.webwallet.auth.dto.RegisterRequest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.auth.token.UserToken;
import com.andrewcorp.webwallet.auth.token.UserTokenRepository;
import com.andrewcorp.webwallet.auth.token.UserTokenType;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.mail.AuthMailEvent;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final TokenService tokenService;
    private final LoginAttemptService loginAttempts;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final AppProperties.Auth properties;
    private final Clock clock;
    /** Compared against when the user does not exist, so that response time does not reveal registered emails. */
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, UserTokenRepository userTokens, TokenService tokenService,
                       LoginAttemptService loginAttempts, PasswordEncoder passwordEncoder, ApplicationEventPublisher events,
                       AppProperties properties, Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.tokenService = tokenService;
        this.loginAttempts = loginAttempts;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.properties = properties.auth();
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(TokenHasher.newToken());
    }

    @Transactional
    public User register(RegisterRequest request, Locale locale) {
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        User user = users.save(new User(email, passwordEncoder.encode(request.password()),
                request.firstName().trim(), request.lastName().trim(), supportedLanguage(locale)));
        sendToken(user, UserTokenType.EMAIL_VERIFICATION);
        return user;
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        UserToken token = useToken(rawToken, UserTokenType.EMAIL_VERIFICATION);
        token.getUser().setEmailVerified(true);
    }

    /** Silently does nothing for unknown or already verified emails, so the response does not reveal accounts. */
    @Transactional
    public void resendVerification(String email) {
        users.findByEmail(User.normalizeEmail(email))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> sendToken(user, UserTokenType.EMAIL_VERIFICATION));
    }

    @Transactional
    public TokenResponse login(LoginRequest request, String clientIp) {
        if (loginAttempts.isBlocked(clientIp)) {
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
        }
        Optional<User> user = users.findByEmail(User.normalizeEmail(request.email()));
        String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
        if (!passwordEncoder.matches(request.password(), hash) || user.isEmpty()) {
            loginAttempts.loginFailed(clientIp);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        loginAttempts.loginSucceeded(clientIp);
        if (!user.get().isEnabled()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!user.get().isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        return tokenService.issueTokens(user.get());
    }

    /** Silently does nothing for unknown emails, so the response does not reveal accounts. */
    @Transactional
    public void forgotPassword(String email) {
        users.findByEmail(User.normalizeEmail(email))
                .filter(User::isEnabled)
                .ifPresent(user -> sendToken(user, UserTokenType.PASSWORD_RESET));
    }

    /** Sets a new password and signs the user out everywhere. */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        User user = useToken(rawToken, UserTokenType.PASSWORD_RESET).getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Following the emailed link proves ownership of the address
        user.setEmailVerified(true);
        tokenService.revokeAllSessions(user);
    }

    /** Changes the password, ends all other sessions and returns tokens for a new one. */
    @Transactional
    public TokenResponse changePassword(Long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        tokenService.revokeAllSessions(user);
        return tokenService.issueTokens(user);
    }

    private void sendToken(User user, UserTokenType type) {
        userTokens.deleteByUserAndType(user, type);
        Duration ttl = type == UserTokenType.EMAIL_VERIFICATION
                ? properties.emailVerificationTtl()
                : properties.passwordResetTtl();
        String rawToken = TokenHasher.newToken();
        userTokens.save(new UserToken(user, type, TokenHasher.hash(rawToken), clock.instant().plus(ttl)));
        AuthMailEvent.Type mailType = type == UserTokenType.EMAIL_VERIFICATION
                ? AuthMailEvent.Type.EMAIL_VERIFICATION
                : AuthMailEvent.Type.PASSWORD_RESET;
        events.publishEvent(new AuthMailEvent(mailType, user.getEmail(), user.getFirstName(),
                Locale.forLanguageTag(user.getLocale()), rawToken));
    }

    private UserToken useToken(String rawToken, UserTokenType type) {
        Instant now = clock.instant();
        UserToken token = userTokens.findByTokenHashAndType(TokenHasher.hash(rawToken), type)
                .filter(candidate -> candidate.isUsable(now))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN));
        token.markUsed(now);
        return token;
    }

    private static String supportedLanguage(Locale locale) {
        return locale != null && "uk".equals(locale.getLanguage()) ? "uk" : "en";
    }
}
