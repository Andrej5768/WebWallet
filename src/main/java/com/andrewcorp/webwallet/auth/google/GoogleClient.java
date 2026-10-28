package com.andrewcorp.webwallet.auth.google;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/** The OpenID Connect endpoints of Google: the consent page, the code exchange and the ID token check. */
@Component
public class GoogleClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleClient.class);

    private static final String AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String KEYS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final AppProperties.Google properties;
    private final RestClient restClient;
    private final NimbusJwtDecoder keys;

    public GoogleClient(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties.google();
        this.restClient = builder.build();
        this.keys = NimbusJwtDecoder.withJwkSetUri(KEYS_URL).build();
        this.keys.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                jwt -> ISSUERS.contains(String.valueOf(jwt.getClaims().get("iss")))
                                && jwt.getAudience().stream().anyMatch(this.properties.audiences()::contains)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "Not an ID token for this client", null))));
    }

    /**
     * The identity behind an ID token a native app got from Google (B3): the signature, issuer, audience and expiry
     * are checked like those of the browser flow; the nonce is not, since the app had no way to set one.
     */
    public GoogleIdentity verifyIdToken(String idToken) {
        try {
            return identity(keys.decode(idToken));
        } catch (JwtException e) {
            log.warn("Google ID token rejected: {}", e.getMessage());
            throw new ApiException(ErrorCode.INVALID_TOKEN);
        }
    }

    /** The Google page that asks the user to choose an account and come back to {@code redirectUri}. */
    public String authorizationUrl(String state, String codeChallenge, String nonce, String redirectUri) {
        return UriComponentsBuilder.fromUriString(AUTHORIZATION_URL)
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("prompt", "select_account")
                .encode()
                .toUriString();
    }

    /**
     * Exchanges the code of the redirect for the identity of the account. The ID token is checked with the keys of
     * Google: signature, issuer, audience, expiry and the nonce of this sign-in. Fails with EXTERNAL_SERVICE_UNAVAILABLE.
     */
    public GoogleIdentity exchange(String code, String codeVerifier, String redirectUri, String nonce) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        try {
            TokenResponse response = restClient
                    .post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.idToken() == null) {
                throw new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
            }
            Jwt token = keys.decode(response.idToken());
            if (!nonce.equals(token.getClaimAsString("nonce"))) {
                throw new JwtException("The nonce does not match");
            }
            return identity(token);
        } catch (RestClientException | JwtException e) {
            log.warn("Google sign-in failed: {}", e.getMessage());
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        }
    }

    private static GoogleIdentity identity(Jwt token) {
        return new GoogleIdentity(
                token.getSubject(),
                token.getClaimAsString("email"),
                Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified")),
                token.getClaimAsString("given_name"),
                token.getClaimAsString("family_name"));
    }

    private record TokenResponse(@JsonProperty("id_token") String idToken) {}
}
