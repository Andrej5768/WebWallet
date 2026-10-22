package com.andrewcorp.webwallet.auth.google;

import com.andrewcorp.webwallet.auth.AuthService;
import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.auth.token.UserToken;
import com.andrewcorp.webwallet.auth.token.UserTokenRepository;
import com.andrewcorp.webwallet.auth.token.UserTokenType;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.common.web.ClientInfo;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.ledger.category.DefaultCategories;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in with Google (OpenID Connect, authorization code with PKCE): signs in a linked account, links a Google
 * account to an account with the same verified email, or creates an account; and links Google to the signed-in user.
 *
 * <p>The callback does not start the session itself: it redirects to the web client with a one-time ticket, which the
 * client exchanges with an ordinary request. The session then starts like after a password, with the second step of
 * two-factor authentication and the trusted browser cookie.
 */
@Service
public class GoogleSignInService {

    public static final String CALLBACK_PATH = "/api/v1/auth/google/callback";
    /** Pages of the web client the callback redirects to; the result goes in the fragment. */
    static final String SIGN_IN_PAGE = "/auth/google";

    static final String SETTINGS_PAGE = "/settings?tab=security";

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration TICKET_TTL = Duration.ofMinutes(2);

    private final AppProperties properties;
    private final GoogleClient google;
    private final OAuthStateRepository states;
    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final AuthService authService;
    private final DefaultCategories defaultCategories;
    private final Clock clock;

    public GoogleSignInService(
            AppProperties properties,
            GoogleClient google,
            OAuthStateRepository states,
            UserRepository users,
            UserTokenRepository userTokens,
            AuthService authService,
            DefaultCategories defaultCategories,
            Clock clock) {
        this.properties = properties;
        this.google = google;
        this.states = states;
        this.users = users;
        this.userTokens = userTokens;
        this.authService = authService;
        this.defaultCategories = defaultCategories;
        this.clock = clock;
    }

    /** The Google page to send the browser to, and the state for its cookie. */
    public record Started(String url, String state) {}

    public boolean enabled() {
        return properties.google().enabled();
    }

    /** Starts a sign-in ({@code userId} null) or the linking of a Google account to the user. */
    @Transactional
    public Started start(OAuthState.Purpose purpose, Long userId) {
        if (!enabled()) {
            throw ApiException.notFound();
        }
        String state = TokenHasher.newToken();
        String verifier = TokenHasher.newToken();
        String nonce = TokenHasher.newToken();
        states.save(new OAuthState(
                TokenHasher.hash(state),
                purpose,
                userId,
                verifier,
                nonce,
                clock.instant().plus(STATE_TTL)));
        return new Started(google.authorizationUrl(state, challenge(verifier), nonce, redirectUri()), state);
    }

    /**
     * Handles the redirect back from Google and returns the path of the web client to go to. The state must match the
     * cookie of the browser that started, so that nobody can make a browser finish a sign-in started elsewhere.
     */
    @Transactional
    public String callback(String stateCookie, String state, String code, String error, Locale locale) {
        if (state == null || stateCookie == null || !constantTimeEquals(state, stateCookie)) {
            return SIGN_IN_PAGE + "#error=failed";
        }
        Optional<OAuthState> found = states.findByStateHash(TokenHasher.hash(state));
        if (found.isEmpty() || states.deleteIfPresent(found.get().getId()) == 0) {
            return SIGN_IN_PAGE + "#error=failed";
        }
        OAuthState started = found.get();
        String page = started.getPurpose() == OAuthState.Purpose.LINK ? SETTINGS_PAGE : SIGN_IN_PAGE;
        if (started.getExpiresAt().isBefore(clock.instant())) {
            return page + "#error=failed";
        }
        if (error != null || code == null) {
            return page + "#error=cancelled";
        }
        GoogleIdentity identity;
        try {
            identity = google.exchange(code, started.getCodeVerifier(), redirectUri(), started.getNonce());
        } catch (ApiException e) {
            return page + "#error=failed";
        }
        return started.getPurpose() == OAuthState.Purpose.LINK
                ? link(started.getUserId(), identity)
                : signIn(identity, locale);
    }

    /** Exchanges the ticket of the callback for a session or the second step of two-factor authentication. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthService.LoginResult complete(String ticket, String trustedDeviceToken, ClientInfo.Kind client) {
        UserToken token = userTokens
                .findByTokenHashAndType(TokenHasher.hash(ticket), UserTokenType.GOOGLE_LOGIN)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN));
        if (userTokens.markUsedIfUsable(token.getId(), clock.instant()) == 0) {
            throw new ApiException(ErrorCode.INVALID_TOKEN);
        }
        User user = token.getUser();
        if (!user.isEnabled()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        return authService.startSession(user, trustedDeviceToken, client);
    }

    /** Unlinks the Google account; an account without a password would be left without a way to sign in. */
    @Transactional
    public void unlink(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (!user.hasPassword()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        user.setGoogleAccount(null, null);
    }

    private String link(Long userId, GoogleIdentity identity) {
        Optional<User> user = users.findById(userId);
        if (user.isEmpty()) {
            return SETTINGS_PAGE + "#error=failed";
        }
        Optional<User> owner = users.findByGoogleSubject(identity.subject());
        if (owner.isPresent() && !owner.get().getId().equals(userId)) {
            return SETTINGS_PAGE + "#error=taken";
        }
        user.get().setGoogleAccount(identity.subject(), identity.email());
        return SETTINGS_PAGE + "#google=linked";
    }

    /**
     * Finds the account of the Google account, links it to an account with the same verified email, or creates one.
     * An unverified account with that email may belong to someone else who typed the address, so it is not linked.
     */
    private String signIn(GoogleIdentity identity, Locale locale) {
        User user = users.findByGoogleSubject(identity.subject()).orElse(null);
        if (user == null) {
            if (!identity.emailVerified() || identity.email() == null) {
                return SIGN_IN_PAGE + "#error=unverified";
            }
            Optional<User> existing = users.findByEmail(User.normalizeEmail(identity.email()));
            if (existing.isPresent() && !existing.get().isEmailVerified()) {
                return SIGN_IN_PAGE + "#error=email_taken";
            }
            user = existing.orElseGet(() -> create(identity, locale));
            user.setGoogleAccount(identity.subject(), identity.email());
        }
        if (!user.isEnabled()) {
            return SIGN_IN_PAGE + "#error=disabled";
        }
        userTokens.deleteByUserAndType(user, UserTokenType.GOOGLE_LOGIN);
        String ticket = TokenHasher.newToken();
        userTokens.save(new UserToken(
                user,
                UserTokenType.GOOGLE_LOGIN,
                TokenHasher.hash(ticket),
                clock.instant().plus(TICKET_TTL)));
        return SIGN_IN_PAGE + "#ticket=" + ticket;
    }

    private User create(GoogleIdentity identity, Locale locale) {
        String email = User.normalizeEmail(identity.email());
        String firstName = identity.givenName() == null || identity.givenName().isBlank()
                ? email.substring(0, email.indexOf('@'))
                : identity.givenName().trim();
        String language = locale != null && "uk".equals(locale.getLanguage()) ? "uk" : "en";
        User user = new User(email, null, firstName, Texts.trimToNull(identity.familyName()), language);
        user.setEmailVerified(true);
        user = users.save(user);
        defaultCategories.create(user.getId(), Locale.forLanguageTag(language));
        return user;
    }

    private String redirectUri() {
        return properties.frontendUrl(CALLBACK_PATH);
    }

    /** PKCE S256: the challenge sent with the authorization request is the hash of the verifier. */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Removes the states of sign-ins that were never finished. */
    @Transactional
    public int purgeExpired(Instant before) {
        return states.deleteExpired(before);
    }
}
