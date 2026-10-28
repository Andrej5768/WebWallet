package com.andrewcorp.webwallet.demo;

import com.andrewcorp.webwallet.auth.IssuedTokens;
import com.andrewcorp.webwallet.auth.TokenService;
import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.auth.token.UserToken;
import com.andrewcorp.webwallet.auth.token.UserTokenRepository;
import com.andrewcorp.webwallet.auth.token.UserTokenType;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.util.AttemptCounter;
import com.andrewcorp.webwallet.common.web.ClientInfo;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.ledger.category.DefaultCategories;
import com.andrewcorp.webwallet.mail.AuthMailEvent;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The demo sandbox: an account opened with one tap, filled with example data, deleted after a day. Each visitor gets
 * their own, so nobody sees or spoils anyone else's. The sandbox can be kept by claiming it with an email and a
 * password, which turns it into an ordinary account with the data the visitor already entered.
 */
@Service
public class DemoService {

    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final TokenService tokenService;
    private final DefaultCategories defaultCategories;
    private final DemoData demoData;
    private final PasswordEncoder passwordEncoder;
    private final MessageSource messages;
    private final ApplicationEventPublisher events;
    private final AppProperties.Demo properties;
    private final AppProperties.Auth auth;
    private final AttemptCounter startsByIp;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DemoService(
            UserRepository users,
            UserTokenRepository userTokens,
            TokenService tokenService,
            DefaultCategories defaultCategories,
            DemoData demoData,
            PasswordEncoder passwordEncoder,
            MessageSource messages,
            ApplicationEventPublisher events,
            AppProperties properties,
            JdbcTemplate jdbc,
            Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.tokenService = tokenService;
        this.defaultCategories = defaultCategories;
        this.demoData = demoData;
        this.passwordEncoder = passwordEncoder;
        this.messages = messages;
        this.events = events;
        this.properties = properties.demo();
        this.auth = properties.auth();
        this.startsByIp = new AttemptCounter(this.properties.maxPerIp(), this.properties.window());
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * Opens a sandbox and starts its session. The user has a random password nobody knows and an address nobody
     * can mail, so the only way in is the session started here; two-factor authentication is off.
     */
    @Transactional
    public IssuedTokens start(Locale locale, String clientIp, ClientInfo.Kind client) {
        if (!properties.enabled()) {
            throw ApiException.notFound();
        }
        String key = clientIp == null ? "unknown" : clientIp;
        if (startsByIp.isExceeded(key)) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
        }
        startsByIp.record(key);
        String language = locale.getLanguage().equals("uk") ? "uk" : "en";
        Locale userLocale = Locale.forLanguageTag(language);
        User user = new User(
                "demo-" + UUID.randomUUID() + "@demo.invalid",
                passwordEncoder.encode(TokenHasher.newToken()),
                message("demo.name", userLocale),
                null,
                language);
        user.setEmailVerified(true);
        user.setDemoExpiresAt(clock.instant().plus(properties.ttl()));
        user = users.save(user);
        defaultCategories.create(user.getId(), userLocale);
        demoData.fill(user.getId(), userLocale);
        return tokenService.issueTokens(user, client);
    }

    /**
     * Keeps the sandbox as an ordinary account: the email and password become the user's own, the data stays, and
     * the email is to be verified through the usual message. Fails for a taken email, since the visitor is already
     * signed in to this account and nothing about other accounts is revealed that signing up would not reveal.
     */
    @Transactional
    public void claim(Long userId, String email, String password, String firstName) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (!user.isDemo()) {
            throw new ApiException(ErrorCode.DEMO_RESTRICTED);
        }
        String normalized = User.normalizeEmail(email);
        if (users.findByEmail(normalized).isPresent()) {
            throw ApiException.invalid("email", "error.emailTaken");
        }
        user.setEmail(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFirstName(firstName.trim());
        user.setEmailVerified(false);
        user.setDemoExpiresAt(null);
        String rawToken = TokenHasher.newToken();
        userTokens.save(new UserToken(
                user,
                UserTokenType.EMAIL_VERIFICATION,
                TokenHasher.hash(rawToken),
                clock.instant().plus(auth.emailVerificationTtl())));
        events.publishEvent(new AuthMailEvent(
                AuthMailEvent.Type.EMAIL_VERIFICATION, normalized, Locale.forLanguageTag(user.getLocale()), rawToken));
    }

    /**
     * Deletes the sandboxes whose day is over, with all their data: operations first, since they refer to accounts
     * and categories without cascading, the rest goes with the user.
     */
    @Transactional
    public int deleteExpired() {
        List<Long> expired = users.findDemoIdsExpiredBefore(clock.instant());
        for (Long userId : expired) {
            jdbc.update("delete from transactions where user_id = ?", userId);
            jdbc.update("delete from investment_operations where user_id = ?", userId);
            jdbc.update("delete from users where id = ?", userId);
        }
        return expired.size();
    }

    private String message(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }
}
