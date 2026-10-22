package com.andrewcorp.webwallet.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String frontendUrl,
        @NotNull Mail mail,
        @NotNull Cors cors,
        @NotNull Jwt jwt,
        @NotNull Auth auth,
        @NotNull Google google,
        @NotNull Telegram telegram,
        @NotNull Android android) {

    /** A page of the web client, e.g. {@code /transactions}; a slash at the end of the configured URL is ignored. */
    public String frontendUrl(String path) {
        return frontendUrl.replaceAll("/+$", "") + path;
    }

    public record Mail(@NotBlank String from) {}

    /**
     * The Telegram bot; it is off while the token is blank.
     *
     * @param botToken      from @BotFather
     * @param botUsername   without the {@code @}, for the t.me links
     * @param webhookSecret Telegram sends it with every webhook request; without it the bot polls for updates
     */
    public record Telegram(
            @DefaultValue("") String botToken,
            @DefaultValue("") String botUsername,
            @DefaultValue("") String webhookSecret) {

        public boolean enabled() {
            return !botToken.isBlank() && !botUsername.isBlank();
        }

        public boolean webhook() {
            return enabled() && !webhookSecret.isBlank();
        }
    }

    public record Cors(List<String> allowedOrigins) {}

    /**
     * Versions of the Android app this server supports.
     *
     * @param minSupportedVersionCode the app refuses to run below it (forced update)
     * @param latestVersionCode the app offers an update below it
     * @param storeUrl where to send the user for the update
     */
    public record Android(
            @DefaultValue("1") @Positive int minSupportedVersionCode,
            @DefaultValue("1") @Positive int latestVersionCode,

            @DefaultValue("https://play.google.com/store/apps/details?id=org.andrewdev.webwallet") @NotBlank
            String storeUrl) {

        public Android {
            if (latestVersionCode < minSupportedVersionCode) {
                throw new IllegalArgumentException("app.android.latest-version-code (" + latestVersionCode
                        + ") must not be below app.android.min-supported-version-code (" + minSupportedVersionCode
                        + ")");
            }
        }
    }

    /**
     * OAuth client of Google sign-in; sign-in with Google is off while the client id is blank.
     *
     * @param clientId of the web application client in Google Cloud
     * @param clientSecret of that client
     */
    public record Google(
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret) {

        public boolean enabled() {
            return !clientId.isBlank() && !clientSecret.isBlank();
        }
    }

    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl) {}

    /**
     * @param maxFailedLogins failed logins after which the client IP is blocked for {@code loginBlockDuration}
     *     and the account for {@code accountBlockDuration}
     * @param maxEmailRequestsPerAddress requests that send an email (registration, verification, password reset)
     *     per address within {@code emailRequestWindow}
     * @param twoFactorChallengeTtl time to enter the two-factor code after the password
     * @param trustedDeviceTtl how long a browser marked as trusted skips the two-factor code
     * @param refreshCookieSecure whether the refresh token cookie is sent over HTTPS only; disabled only for local
     *     development over plain HTTP
     */
    public record Auth(
            @NotNull Duration emailVerificationTtl,
            @NotNull Duration passwordResetTtl,
            @Positive int maxFailedLogins,
            @NotNull Duration loginBlockDuration,
            @NotNull Duration accountBlockDuration,
            @Positive int maxEmailRequestsPerAddress,
            @Positive int maxEmailRequestsPerIp,
            @NotNull Duration emailRequestWindow,
            @NotNull Duration twoFactorChallengeTtl,
            @NotNull Duration trustedDeviceTtl,
            @NotBlank String refreshCookieName,
            @DefaultValue("true") boolean refreshCookieSecure) {}
}
