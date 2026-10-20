package com.andrewcorp.webwallet.auth.twofactor;

import com.andrewcorp.webwallet.auth.AuthRateLimiter;
import com.andrewcorp.webwallet.auth.IssuedTokens;
import com.andrewcorp.webwallet.auth.TokenService;
import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.auth.token.UserToken;
import com.andrewcorp.webwallet.auth.token.UserTokenRepository;
import com.andrewcorp.webwallet.auth.token.UserTokenType;
import com.andrewcorp.webwallet.common.crypto.SecretCipher;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-factor authentication with a TOTP authenticator app: setup, recovery codes, trusted browsers and the second
 * step of signing in.
 */
@Service
public class TwoFactorService {

    public static final int RECOVERY_CODE_COUNT = 10;
    private static final String ISSUER = "WebWallet";
    /** Without 0/O, 1/I, so that a code read from paper is typed right. */
    private static final String RECOVERY_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RecoveryCodeRepository recoveryCodes;
    private final TrustedDeviceRepository trustedDevices;
    private final UserTokenRepository userTokens;
    private final TokenService tokenService;
    private final AuthRateLimiter rateLimiter;
    private final SecretCipher cipher;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties.Auth properties;
    private final Clock clock;

    public TwoFactorService(
            UserRepository users,
            RecoveryCodeRepository recoveryCodes,
            TrustedDeviceRepository trustedDevices,
            UserTokenRepository userTokens,
            TokenService tokenService,
            AuthRateLimiter rateLimiter,
            SecretCipher cipher,
            PasswordEncoder passwordEncoder,
            AppProperties properties,
            Clock clock) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.trustedDevices = trustedDevices;
        this.userTokens = userTokens;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties.auth();
        this.clock = clock;
    }

    /** The secret and the link of a QR code for the authenticator app, see {@link #setup}. */
    public record Setup(String secret, String otpauthUri) {}

    /** A session after the second step; {@code trustedDeviceToken} is set when the browser is to be trusted. */
    public record Login(IssuedTokens tokens, String trustedDeviceToken) {}

    /** Generates a secret that becomes active once {@link #enable} gets a code of it. A new setup replaces it. */
    @Transactional
    public Setup setup(Long userId) {
        User user = user(userId);
        if (user.isTwoFactorEnabled()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        String secret = Totp.newSecret();
        user.setTotpPendingSecret(cipher.encrypt(secret));
        return new Setup(secret, Totp.uri(ISSUER, user.getEmail(), secret));
    }

    /** Turns two-factor authentication on with a code of the pending secret and returns new recovery codes. */
    @Transactional(noRollbackFor = ApiException.class)
    public List<String> enable(Long userId, String code) {
        User user = user(userId);
        if (user.isTwoFactorEnabled() || user.getTotpPendingSecret() == null) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        rateLimiter.checkTwoFactor(userId);
        long step = Totp.verify(cipher.decrypt(user.getTotpPendingSecret()), code, clock.instant(), null);
        if (step < 0) {
            rateLimiter.twoFactorFailed(userId);
            throw new ApiException(ErrorCode.INVALID_TWO_FACTOR_CODE);
        }
        user.setTotpSecret(user.getTotpPendingSecret(), clock.instant());
        user.setTotpLastStep(step);
        trustedDevices.deleteByUserId(userId);
        return newRecoveryCodes(userId);
    }

    /**
     * Turns two-factor authentication off. Needs the password, when the account has one, and a code of the app or a
     * recovery code.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void disable(Long userId, String password, String code) {
        User user = user(userId);
        if (!user.isTwoFactorEnabled()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        checkPassword(user, password);
        verifySecondFactor(user, code);
        user.setTotpSecret(null, clock.instant());
        user.setTotpLastStep(null);
        recoveryCodes.deleteByUserId(userId);
        trustedDevices.deleteByUserId(userId);
    }

    /** Replaces the recovery codes; needs a code of the app, not a recovery code. */
    @Transactional(noRollbackFor = ApiException.class)
    public List<String> regenerateRecoveryCodes(Long userId, String code) {
        User user = user(userId);
        if (!user.isTwoFactorEnabled()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        rateLimiter.checkTwoFactor(userId);
        if (!verifyAppCode(user, code)) {
            rateLimiter.twoFactorFailed(userId);
            throw new ApiException(ErrorCode.INVALID_TWO_FACTOR_CODE);
        }
        return newRecoveryCodes(userId);
    }

    public long recoveryCodesLeft(Long userId) {
        return recoveryCodes.countByUserIdAndUsedAtIsNull(userId);
    }

    /** Whether the browser of the trusted device cookie may skip the code for this user. */
    public boolean isTrusted(User user, String rawDeviceToken) {
        if (rawDeviceToken == null || rawDeviceToken.isBlank()) {
            return false;
        }
        return trustedDevices
                .findByTokenHash(TokenHasher.hash(rawDeviceToken))
                .filter(device -> device.getUserId().equals(user.getId()))
                .filter(device -> device.getExpiresAt().isAfter(clock.instant()))
                .isPresent();
    }

    /** Starts the second step of signing in; the returned challenge is exchanged by {@link #completeLogin}. */
    @Transactional
    public String startLogin(User user) {
        userTokens.deleteByUserAndType(user, UserTokenType.TWO_FACTOR_LOGIN);
        String challenge = TokenHasher.newToken();
        userTokens.save(new UserToken(
                user,
                UserTokenType.TWO_FACTOR_LOGIN,
                TokenHasher.hash(challenge),
                clock.instant().plus(properties.twoFactorChallengeTtl())));
        return challenge;
    }

    /**
     * Exchanges the challenge and a code of the app or a recovery code for a session. A wrong code keeps the challenge,
     * so that the user can try again until it expires or the attempts run out.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Login completeLogin(String challenge, String code, boolean trustDevice) {
        Instant now = clock.instant();
        UserToken token = userTokens
                .findByTokenHashAndType(TokenHasher.hash(challenge), UserTokenType.TWO_FACTOR_LOGIN)
                .filter(found ->
                        found.getUsedAt() == null && found.getExpiresAt().isAfter(now))
                .orElseThrow(() -> new ApiException(ErrorCode.TWO_FACTOR_EXPIRED));
        User user = token.getUser();
        verifySecondFactor(user, code);
        if (userTokens.markUsedIfUsable(token.getId(), now) == 0) {
            throw new ApiException(ErrorCode.TWO_FACTOR_EXPIRED);
        }
        if (!user.isEnabled()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
        String deviceToken = trustDevice ? trust(user) : null;
        return new Login(tokenService.issueTokens(user), deviceToken);
    }

    /** Forgets the trusted browsers, e.g. when the password changes. */
    @Transactional
    public void forgetDevices(Long userId) {
        trustedDevices.deleteByUserId(userId);
    }

    private String trust(User user) {
        String raw = TokenHasher.newToken();
        trustedDevices.save(new TrustedDevice(
                user.getId(), TokenHasher.hash(raw), clock.instant().plus(properties.trustedDeviceTtl())));
        return raw;
    }

    /** Accepts a code of the app or an unused recovery code; counts failures against the user. */
    private void verifySecondFactor(User user, String code) {
        rateLimiter.checkTwoFactor(user.getId());
        if (verifyAppCode(user, code) || useRecoveryCode(user, code)) {
            return;
        }
        rateLimiter.twoFactorFailed(user.getId());
        throw new ApiException(ErrorCode.INVALID_TWO_FACTOR_CODE);
    }

    private boolean verifyAppCode(User user, String code) {
        long step = Totp.verify(cipher.decrypt(user.getTotpSecret()), code, clock.instant(), user.getTotpLastStep());
        if (step < 0) {
            return false;
        }
        user.setTotpLastStep(step);
        return true;
    }

    private boolean useRecoveryCode(User user, String code) {
        if (code == null) {
            return false;
        }
        String normalized = normalizeRecoveryCode(code);
        if (normalized.length() != 8) {
            return false;
        }
        return recoveryCodes
                .findFirstByUserIdAndCodeHashAndUsedAtIsNull(user.getId(), TokenHasher.hash(normalized))
                .filter(found -> recoveryCodes.markUsedIfUnused(found.getId(), clock.instant()) == 1)
                .isPresent();
    }

    private List<String> newRecoveryCodes(Long userId) {
        recoveryCodes.deleteByUserId(userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            StringBuilder code = new StringBuilder();
            for (int c = 0; c < 8; c++) {
                code.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            recoveryCodes.save(new RecoveryCode(userId, TokenHasher.hash(code.toString())));
            codes.add(code.substring(0, 4) + "-" + code.substring(4));
        }
        return codes;
    }

    static String normalizeRecoveryCode(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private void checkPassword(User user, String password) {
        if (user.getPasswordHash() == null) {
            return;
        }
        rateLimiter.checkPasswordChange(user.getId());
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            rateLimiter.passwordCheckFailed(user.getId());
            throw new ApiException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
    }

    private User user(Long userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }
}
