package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.dto.LoginRequest;
import com.andrewcorp.webwallet.auth.dto.RegisterRequest;
import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.auth.token.UserToken;
import com.andrewcorp.webwallet.auth.token.UserTokenRepository;
import com.andrewcorp.webwallet.auth.token.UserTokenType;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.ledger.category.DefaultCategories;
import com.andrewcorp.webwallet.mail.AuthMailEvent;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final TokenService tokenService;
    private final AuthRateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final AppProperties.Auth properties;
    private final DefaultCategories defaultCategories;
    private final Clock clock;
    /** Compared against when the user does not exist, so that response time does not reveal registered emails. */
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository users,
            UserTokenRepository userTokens,
            TokenService tokenService,
            AuthRateLimiter rateLimiter,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            AppProperties properties,
            DefaultCategories defaultCategories,
            Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.properties = properties.auth();
        this.defaultCategories = defaultCategories;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(TokenHasher.newToken());
    }

    /**
     * Registers a user and sends the verification email. For a taken email the owner gets an email instead and the
     * response is the same, so that registration does not reveal accounts.
     */
    @Transactional
    public void register(RegisterRequest request, Locale locale, String clientIp) {
        String email = User.normalizeEmail(request.email());
        rateLimiter.emailRequested(clientIp, email);
        // Hashed for a taken email as well, so that the response time is the same
        String passwordHash = passwordEncoder.encode(request.password());
        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            events.publishEvent(
                    new AuthMailEvent(AuthMailEvent.Type.ACCOUNT_EXISTS, email, locale(existing.get()), null));
            return;
        }
        User user = users.save(new User(
                email,
                passwordHash,
                request.firstName().trim(),
                Texts.trimToNull(request.lastName()),
                supportedLanguage(locale)));
        defaultCategories.create(user.getId(), locale(user));
        sendToken(user, UserTokenType.EMAIL_VERIFICATION);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        UserToken token = useToken(rawToken, UserTokenType.EMAIL_VERIFICATION);
        token.getUser().setEmailVerified(true);
    }

    /** Silently does nothing for unknown or already verified emails, so the response does not reveal accounts. */
    @Transactional
    public void resendVerification(String email, String clientIp) {
        String normalized = User.normalizeEmail(email);
        rateLimiter.emailRequested(clientIp, normalized);
        users.findByEmail(normalized)
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> sendToken(user, UserTokenType.EMAIL_VERIFICATION));
    }

    @Transactional
    public IssuedTokens login(LoginRequest request, String clientIp) {
        String email = User.normalizeEmail(request.email());
        rateLimiter.checkLogin(clientIp, email);
        Optional<User> user = users.findByEmail(email);
        String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
        if (!passwordEncoder.matches(request.password(), hash) || user.isEmpty()) {
            rateLimiter.loginFailed(clientIp, email);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        rateLimiter.loginSucceeded(email);
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
    public void forgotPassword(String email, String clientIp) {
        String normalized = User.normalizeEmail(email);
        rateLimiter.emailRequested(clientIp, normalized);
        users.findByEmail(normalized)
                .filter(User::isEnabled)
                .ifPresent(user -> sendToken(user, UserTokenType.PASSWORD_RESET));
    }

    /** Sets a new password and signs the user out everywhere. */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        User user = useToken(rawToken, UserTokenType.PASSWORD_RESET).getUser();
        setPassword(user, newPassword);
        // Following the emailed link proves ownership of the address
        user.setEmailVerified(true);
    }

    /** Changes the password, ends all other sessions and returns tokens for a new one. */
    @Transactional
    public IssuedTokens changePassword(Long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        rateLimiter.checkPasswordChange(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            rateLimiter.passwordCheckFailed(userId);
            throw new ApiException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
        setPassword(user, newPassword);
        return tokenService.issueTokens(user);
    }

    /** Ends all sessions and voids reset links that were sent for the old password. */
    private void setPassword(User user, String newPassword) {
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userTokens.deleteByUserAndType(user, UserTokenType.PASSWORD_RESET);
        tokenService.revokeAllSessions(user);
    }

    private void sendToken(User user, UserTokenType type) {
        userTokens.deleteByUserAndType(user, type);
        Duration ttl = type == UserTokenType.EMAIL_VERIFICATION
                ? properties.emailVerificationTtl()
                : properties.passwordResetTtl();
        String rawToken = TokenHasher.newToken();
        userTokens.save(new UserToken(
                user, type, TokenHasher.hash(rawToken), clock.instant().plus(ttl)));
        AuthMailEvent.Type mailType = type == UserTokenType.EMAIL_VERIFICATION
                ? AuthMailEvent.Type.EMAIL_VERIFICATION
                : AuthMailEvent.Type.PASSWORD_RESET;
        events.publishEvent(new AuthMailEvent(mailType, user.getEmail(), locale(user), rawToken));
    }

    /** Marks the token used with a conditional update, so that a token cannot be used twice concurrently. */
    private UserToken useToken(String rawToken, UserTokenType type) {
        UserToken token = userTokens
                .findByTokenHashAndType(TokenHasher.hash(rawToken), type)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN));
        if (userTokens.markUsedIfUsable(token.getId(), clock.instant()) == 0) {
            throw new ApiException(ErrorCode.INVALID_TOKEN);
        }
        return token;
    }

    private static Locale locale(User user) {
        return Locale.forLanguageTag(user.getLocale());
    }

    private static String supportedLanguage(Locale locale) {
        return locale != null && "uk".equals(locale.getLanguage()) ? "uk" : "en";
    }
}
