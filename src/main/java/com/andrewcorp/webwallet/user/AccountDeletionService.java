package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.auth.AuthRateLimiter;
import com.andrewcorp.webwallet.auth.IssuedTokens;
import com.andrewcorp.webwallet.auth.TokenService;
import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.auth.token.UserToken;
import com.andrewcorp.webwallet.auth.token.UserTokenRepository;
import com.andrewcorp.webwallet.auth.token.UserTokenType;
import com.andrewcorp.webwallet.auth.twofactor.TwoFactorService;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.mail.AuthMailEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes an account at the user's request after a grace period. Until then the account is blocked: signing in
 * offers to cancel instead of starting a session, and the email sent at the request has a link that cancels too.
 */
@Service
public class AccountDeletionService {

    public static final Duration GRACE_PERIOD = Duration.ofDays(14);

    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final TokenService tokenService;
    private final TwoFactorService twoFactor;
    private final AuthRateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccountDeletionService(
            UserRepository users,
            UserTokenRepository userTokens,
            TokenService tokenService,
            TwoFactorService twoFactor,
            AuthRateLimiter rateLimiter,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            JdbcTemplate jdbc,
            Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.tokenService = tokenService;
        this.twoFactor = twoFactor;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Schedules the deletion and ends all sessions. Needs the password, or for an account without one (signed up
     * with Google) its email typed again, and the two-factor code when that is on. Returns when it is deleted.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Instant requestDeletion(Long userId, String password, String code, String confirmEmail) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (user.hasPassword()) {
            rateLimiter.checkPasswordChange(userId);
            if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
                rateLimiter.passwordCheckFailed(userId);
                throw new ApiException(ErrorCode.INVALID_CURRENT_PASSWORD);
            }
        } else if (confirmEmail == null || !User.normalizeEmail(confirmEmail).equals(user.getEmail())) {
            throw ApiException.invalid("confirmEmail", "validation.confirmEmail");
        }
        if (user.isTwoFactorEnabled()) {
            twoFactor.checkCode(user, code);
        }
        Instant at = clock.instant().plus(GRACE_PERIOD);
        user.setDeletionScheduledAt(at);
        tokenService.revokeAllSessions(user);
        twoFactor.forgetDevices(userId);
        userTokens.deleteByUserAndType(user, UserTokenType.ACCOUNT_RESTORE);
        String restoreToken = TokenHasher.newToken();
        userTokens.save(new UserToken(user, UserTokenType.ACCOUNT_RESTORE, TokenHasher.hash(restoreToken), at));
        events.publishEvent(new AuthMailEvent(
                AuthMailEvent.Type.ACCOUNT_DELETION,
                user.getEmail(),
                Locale.forLanguageTag(user.getLocale()),
                restoreToken,
                at));
        return at;
    }

    /**
     * Cancels the deletion. The token of a sign-in also starts a session; the emailed one does not, since a link
     * must not skip the password and the two-factor code.
     *
     * @return the session, or null for the emailed token
     */
    @Transactional
    public IssuedTokens restore(String rawToken) {
        String hash = TokenHasher.hash(rawToken);
        Optional<UserToken> afterSignIn =
                userTokens.findByTokenHashAndType(hash, UserTokenType.ACCOUNT_RESTORE_SESSION);
        UserToken token = afterSignIn
                .or(() -> userTokens.findByTokenHashAndType(hash, UserTokenType.ACCOUNT_RESTORE))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN));
        if (userTokens.markUsedIfUsable(token.getId(), clock.instant()) == 0) {
            throw new ApiException(ErrorCode.INVALID_TOKEN);
        }
        User user = token.getUser();
        user.setDeletionScheduledAt(null);
        userTokens.deleteByUserAndType(user, UserTokenType.ACCOUNT_RESTORE);
        return afterSignIn.isPresent() ? tokenService.issueTokens(user) : null;
    }

    /**
     * Deletes the accounts whose grace period is over. Most data goes with the user through cascading foreign keys;
     * operations go first, since they refer to accounts, categories and instruments without cascading.
     */
    @Transactional
    public int deleteDue() {
        List<Long> due = users.findIdsScheduledBefore(clock.instant());
        for (Long userId : due) {
            jdbc.update("delete from transactions where user_id = ?", userId);
            jdbc.update("delete from investment_operations where user_id = ?", userId);
            jdbc.update("delete from users where id = ?", userId);
        }
        return due.size();
    }
}
