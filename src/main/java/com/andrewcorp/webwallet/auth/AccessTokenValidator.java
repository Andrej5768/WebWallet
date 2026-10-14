package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.user.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Rejects access tokens of disabled or deleted users and tokens issued before the password was changed or reset,
 * so that ending all sessions takes effect immediately instead of when the access tokens expire.
 */
@Component
public class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult SESSION_ENDED = OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The session has ended", null));

    private final UserRepository users;

    public AccessTokenValidator(UserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!(jwt.getClaims().get(TokenService.TOKEN_VERSION_CLAIM) instanceof Number version)) {
            return SESSION_ENDED;
        }
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return SESSION_ENDED;
        }
        return users.findActiveTokenVersion(userId)
                        .filter(current -> current == version.intValue())
                        .isPresent()
                ? OAuth2TokenValidatorResult.success()
                : SESSION_ENDED;
    }
}
