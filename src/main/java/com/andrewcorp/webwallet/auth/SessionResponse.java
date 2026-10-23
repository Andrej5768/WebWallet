package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.auth.dto.LoginResponse;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.twofactor.TrustedDeviceCookie;
import com.andrewcorp.webwallet.common.web.ClientInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Hands a new session to the client the way it can keep it. The web client gets the refresh token and the
 * trusted-device token in HttpOnly cookies, so that scripts cannot read them and, being {@code SameSite=Strict},
 * they are never sent with a cross-site request. A native app has no cookie jar worth the name and no cross-site
 * requests either: it gets both tokens in the JSON body, over HTTPS only, and stores them encrypted on the device.
 * The security filter chain marks every response {@code Cache-Control: no-store}, so a body with a token is not
 * cached.
 */
@Component
public class SessionResponse {

    private final RefreshTokenCookie refreshTokenCookie;
    private final TrustedDeviceCookie trustedDeviceCookie;

    public SessionResponse(RefreshTokenCookie refreshTokenCookie, TrustedDeviceCookie trustedDeviceCookie) {
        this.refreshTokenCookie = refreshTokenCookie;
        this.trustedDeviceCookie = trustedDeviceCookie;
    }

    /** The session: the access token in the body, the refresh token where the client keeps it. */
    public ResponseEntity<TokenResponse> tokens(IssuedTokens tokens, ClientInfo client) {
        return tokens(tokens, null, client);
    }

    /**
     * The session and, after a two-factor sign-in with trustDevice, the token that skips the code next time.
     *
     * @param trustedDeviceToken null when the device is not to be trusted
     */
    public ResponseEntity<TokenResponse> tokens(IssuedTokens tokens, String trustedDeviceToken, ClientInfo client) {
        if (client.nativeApp()) {
            return ResponseEntity.ok(TokenResponse.bearer(tokens, true, trustedDeviceToken));
        }
        ResponseEntity.BodyBuilder response =
                ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshTokenCookie.of(tokens));
        if (trustedDeviceToken != null) {
            response.header(HttpHeaders.SET_COOKIE, trustedDeviceCookie.of(trustedDeviceToken));
        }
        return response.body(TokenResponse.bearer(tokens, false, null));
    }

    /** A session like {@link #tokens(IssuedTokens, ClientInfo)}, or the challenge of the second step without tokens. */
    public ResponseEntity<LoginResponse> login(AuthService.LoginResult result, ClientInfo client) {
        if (result.tokens() == null) {
            return ResponseEntity.ok(LoginResponse.challenge(result.twoFactorChallenge()));
        }
        if (client.nativeApp()) {
            return ResponseEntity.ok(LoginResponse.session(result.tokens(), true));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.of(result.tokens()))
                .body(LoginResponse.session(result.tokens(), false));
    }

    /**
     * The token a request presents: the one in the body when there is one, else the cookie. Both kinds of client
     * are served either way; only the response depends on the client.
     *
     * @return null when neither carries a token
     */
    public static String presented(String bodyToken, String cookieToken) {
        return bodyToken != null && !bodyToken.isBlank() ? bodyToken : cookieToken;
    }
}
