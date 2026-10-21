package com.andrewcorp.webwallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.token.RefreshTokenRepository;
import com.andrewcorp.webwallet.user.Role;
import com.andrewcorp.webwallet.user.User;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private TokenService tokenService;

    private Map<String, String> registration(String email) {
        return Map.of("email", email, "password", PASSWORD, "firstName", "John", "lastName", "Doe");
    }

    // registration and email verification

    @Test
    void registerCreatesUnverifiedUserAndSendsVerificationEmail() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/register", registration("John@Test.com")))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        User user = users.findByEmail("john@test.com").orElseThrow();
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(user.getFirstName()).isEqualTo("John");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getLocale()).isEqualTo("en");

        SimpleMailMessage mail = lastMail();
        assertThat(mail.getTo()).containsExactly("john@test.com");
        assertThat(mail.getSubject()).isEqualTo("Confirm your email");
        // The token is in the fragment, which is not sent to servers
        assertThat(mail.getText()).contains("Hello!", "http://localhost:5173/verify-email#token=", "24 hours");
        // The address may belong to someone else, so the mail contains nothing entered at registration
        assertThat(mail.getText()).doesNotContain("John");
    }

    @Test
    void registerUsesUkrainianForEmailsWhenRequested() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/register", registration("uk@test.com"))
                        .header("Accept-Language", "uk-UA"))
                .andExpect(status().isAccepted());

        assertThat(users.findByEmail("uk@test.com").orElseThrow().getLocale()).isEqualTo("uk");
        assertThat(lastMail().getSubject()).isEqualTo("Підтвердження електронної пошти");
    }

    @Test
    void registerWithTakenEmailLooksTheSameAndNotifiesTheOwner() throws Exception {
        User owner = createUser("taken@test.com", true);

        mockMvc.perform(postJson("/api/v1/auth/register", registration("TAKEN@test.com")))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        SimpleMailMessage mail = lastMail();
        assertThat(mail.getTo()).containsExactly("taken@test.com");
        assertThat(mail.getSubject()).isEqualTo("You already have an account");
        assertThat(mail.getText()).contains("http://localhost:5173/forgot-password");
        // The existing account is not changed
        assertThat(users.findByEmail("taken@test.com").orElseThrow().getPasswordHash())
                .isEqualTo(owner.getPasswordHash());
        assertThat(users.count()).isOne();
    }

    @Test
    void lastNameIsOptional() throws Exception {
        mockMvc.perform(postJson(
                        "/api/v1/auth/register",
                        Map.of("email", "solo@test.com", "password", PASSWORD, "firstName", "Solo")))
                .andExpect(status().isAccepted());
        mockMvc.perform(postJson(
                        "/api/v1/auth/register",
                        Map.of("email", "blank@test.com", "password", PASSWORD, "firstName", "B", "lastName", "  ")))
                .andExpect(status().isAccepted());

        assertThat(users.findByEmail("solo@test.com").orElseThrow().getLastName())
                .isNull();
        assertThat(users.findByEmail("blank@test.com").orElseThrow().getLastName())
                .isNull();
        assertThat(lastMail().getText()).doesNotContain("null");
    }

    @Test
    void registerValidatesInput() throws Exception {
        mockMvc.perform(postJson(
                        "/api/v1/auth/register",
                        Map.of("email", "not-an-email", "password", "short", "firstName", "", "lastName", "Doe")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("firstName")));
    }

    @Test
    void passwordsLongerThanBcryptCanHashAreRejected() throws Exception {
        // 40 Cyrillic letters are 80 bytes in UTF-8, more than the 72 BCrypt uses
        Map<String, String> body = new HashMap<>(registration("long@test.com"));
        body.put("password", "п".repeat(40));

        mockMvc.perform(postJson("/api/v1/auth/register", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        body.put("password", "п".repeat(36));
        mockMvc.perform(postJson("/api/v1/auth/register", body)).andExpect(status().isAccepted());
    }

    @Test
    void oversizedLoginFieldsAreRejected() throws Exception {
        mockMvc.perform(postJson(
                        "/api/v1/auth/login", Map.of("email", "a".repeat(10_000) + "@test.com", "password", PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registrationSucceedsEvenIfEmailCannotBeSent() throws Exception {
        doThrow(new MailSendException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));

        mockMvc.perform(postJson("/api/v1/auth/register", registration("nomail@test.com")))
                .andExpect(status().isAccepted());

        assertThat(users.findByEmail("nomail@test.com")).isPresent();
    }

    @Test
    void verifyEmailWithTokenFromEmail() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/register", registration("verify@test.com")));
        String token = tokenFromLastMail();

        mockMvc.perform(postJson("/api/v1/auth/verify-email", Map.of("token", token)))
                .andExpect(status().isNoContent());

        assertThat(users.findByEmail("verify@test.com").orElseThrow().isEmailVerified())
                .isTrue();

        // The token can be used only once
        mockMvc.perform(postJson("/api/v1/auth/verify-email", Map.of("token", token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void verifyEmailWithUnknownToken() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/verify-email", Map.of("token", "unknown")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void resendVerificationReplacesPreviousToken() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/register", registration("resend@test.com")));
        String firstToken = tokenFromLastMail();

        mockMvc.perform(postJson("/api/v1/auth/resend-verification", Map.of("email", "resend@test.com")))
                .andExpect(status().isAccepted());
        String secondToken = tokenFromLastMail();

        assertThat(secondToken).isNotEqualTo(firstToken);
        mockMvc.perform(postJson("/api/v1/auth/verify-email", Map.of("token", firstToken)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(postJson("/api/v1/auth/verify-email", Map.of("token", secondToken)))
                .andExpect(status().isNoContent());
    }

    @Test
    void resendVerificationForUnknownOrVerifiedEmailSendsNothing() throws Exception {
        createUser("verified@test.com", true);

        mockMvc.perform(postJson("/api/v1/auth/resend-verification", Map.of("email", "unknown@test.com")))
                .andExpect(status().isAccepted());
        mockMvc.perform(postJson("/api/v1/auth/resend-verification", Map.of("email", "verified@test.com")))
                .andExpect(status().isAccepted());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    // login

    @Test
    void loginReturnsTokensThatAuthenticateRequests() throws Exception {
        User user = createUser("login@test.com", true);

        TokenResponse tokens = login("LOGIN@test.com", PASSWORD);

        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isEqualTo(15 * 60);
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()));
    }

    @Test
    void loginSetsTheRefreshTokenInAnHttpOnlyCookie() throws Exception {
        createUser("cookie@test.com", true);

        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "cookie@test.com", "password", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                // Scripts of the web client must not be able to read the refresh token
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().value(REFRESH_COOKIE, not(emptyString())))
                .andExpect(cookie().httpOnly(REFRESH_COOKIE, true))
                .andExpect(cookie().secure(REFRESH_COOKIE, true))
                .andExpect(cookie().sameSite(REFRESH_COOKIE, "Strict"))
                .andExpect(cookie().path(REFRESH_COOKIE, "/api/v1/auth"))
                .andExpect(cookie().maxAge(REFRESH_COOKIE, 30 * 24 * 60 * 60));
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmail() throws Exception {
        createUser("login@test.com", true);

        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "login@test.com", "password", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "nobody@test.com", "password", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginRequiresVerifiedEmail() throws Exception {
        createUser("unverified@test.com", false);

        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "unverified@test.com", "password", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void loginOfDisabledAccountIsForbidden() throws Exception {
        User user = createUser("disabled@test.com", true);
        user.setEnabled(false);
        users.save(user);

        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "disabled@test.com", "password", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void clientIsBlockedAfterTooManyFailedLogins() throws Exception {
        createUser("blocked@test.com", true);
        String ip = "10.1.2.3";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "blocked@test.com", "password", "wrong"))
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            }))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "blocked@test.com", "password", PASSWORD))
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    @Test
    void fewWrongPasswordsFromElsewhereDoNotLockTheOwnerOut() throws Exception {
        createUser("owner@test.com", true);
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(withIp(
                            postJson("/api/v1/auth/login", Map.of("email", "owner@test.com", "password", "wrong")),
                            "10.0.2.1"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(withIp(
                        postJson("/api/v1/auth/login", Map.of("email", "owner@test.com", "password", PASSWORD)),
                        "10.0.3.1"))
                .andExpect(status().isOk());
    }

    @Test
    void accountIsBlockedAfterTooManyFailedLoginsFromDifferentIps() throws Exception {
        createUser("target@test.com", true);

        for (int i = 0; i < 30; i++) {
            mockMvc.perform(withIp(
                            postJson("/api/v1/auth/login", Map.of("email", "target@test.com", "password", "wrong")),
                            "10.0.0." + i))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(withIp(
                        postJson("/api/v1/auth/login", Map.of("email", "target@test.com", "password", PASSWORD)),
                        "10.0.1.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        // Other accounts are not affected
        createUser("other@test.com", true);
        mockMvc.perform(withIp(
                        postJson("/api/v1/auth/login", Map.of("email", "other@test.com", "password", PASSWORD)),
                        "10.0.1.1"))
                .andExpect(status().isOk());
    }

    @Test
    void signingInToOwnAccountDoesNotUnblockTheIp() throws Exception {
        createUser("own@test.com", true);
        String ip = "10.4.5.6";

        for (int i = 0; i < 10; i++) {
            String email = "victim" + i + "@test.com";
            mockMvc.perform(withIp(postJson("/api/v1/auth/login", Map.of("email", email, "password", "wrong")), ip))
                    .andExpect(status().isUnauthorized());
            if (i == 5) {
                mockMvc.perform(withIp(
                                postJson("/api/v1/auth/login", Map.of("email", "own@test.com", "password", PASSWORD)),
                                ip))
                        .andExpect(status().isOk());
            }
        }

        mockMvc.perform(withIp(
                        postJson("/api/v1/auth/login", Map.of("email", "own@test.com", "password", PASSWORD)), ip))
                .andExpect(status().isTooManyRequests());
    }

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder request, String ip) {
        return request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    @Test
    void requestsThatSendEmailsAreLimitedPerAddress() throws Exception {
        createUser("flood@test.com", true);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(withIp(
                            postJson("/api/v1/auth/forgot-password", Map.of("email", "flood@test.com")), "10.0.2." + i))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(withIp(postJson("/api/v1/auth/forgot-password", Map.of("email", "flood@test.com")), "10.0.3.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        // The limit does not depend on whether the account exists
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(postJson("/api/v1/auth/resend-verification", Map.of("email", "nobody@test.com")))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(postJson("/api/v1/auth/register", registration("nobody@test.com")))
                .andExpect(status().isTooManyRequests());
        assertThat(users.findByEmail("nobody@test.com")).isEmpty();
    }

    @Test
    void ipv6ClientsAndPlusAddressesCountTogether() {
        assertThat(AuthRateLimiter.clientKey("2001:db8:1:2:aaaa::1"))
                .isEqualTo(AuthRateLimiter.clientKey("2001:db8:1:2:bbbb::2"))
                .isNotEqualTo(AuthRateLimiter.clientKey("2001:db8:1:3::1"));
        assertThat(AuthRateLimiter.clientKey("10.0.0.1")).isEqualTo("10.0.0.1");
        assertThat(AuthRateLimiter.mailboxKey("user+1@test.com")).isEqualTo("user@test.com");
        assertThat(AuthRateLimiter.mailboxKey("user@test.com")).isEqualTo("user@test.com");
    }

    @Test
    void requestsThatSendEmailsAreLimitedPerIp() throws Exception {
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(withIp(
                            postJson("/api/v1/auth/forgot-password", Map.of("email", "user" + i + "@test.com")),
                            "10.0.4.1"))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(withIp(postJson("/api/v1/auth/register", registration("fresh@test.com")), "10.0.4.1"))
                .andExpect(status().isTooManyRequests());
    }

    // refresh and logout

    @Test
    void refreshRotatesTheRefreshTokenCookie() throws Exception {
        createUser("refresh@test.com", true);
        Cookie cookie = refreshCookie(loginResponse("refresh@test.com", PASSWORD));

        MockHttpServletResponse response = mockMvc.perform(refresh(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly(REFRESH_COOKIE, true))
                .andExpect(cookie().sameSite(REFRESH_COOKIE, "Strict"))
                .andExpect(cookie().path(REFRESH_COOKIE, "/api/v1/auth"))
                .andReturn()
                .getResponse();

        assertThat(refreshCookie(response).getValue()).isNotEqualTo(cookie.getValue());
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens(response))))
                .andExpect(status().isOk());
    }

    @Test
    void reusingRotatedRefreshTokenRevokesTheSession() throws Exception {
        createUser("reuse@test.com", true);
        Cookie cookie = refreshCookie(loginResponse("reuse@test.com", PASSWORD));
        Cookie refreshed =
                refreshCookie(mockMvc.perform(refresh(cookie)).andReturn().getResponse());

        // The old token is presented again, e.g. by an attacker who stole it
        mockMvc.perform(refresh(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // The legitimate newer token is revoked as well
        mockMvc.perform(refresh(refreshed)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithUnknownToken() throws Exception {
        mockMvc.perform(refresh(new Cookie(REFRESH_COOKIE, "unknown")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshWithoutCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshTokenInTheBodyIsNotAccepted() throws Exception {
        createUser("body@test.com", true);
        Cookie cookie = refreshCookie(loginResponse("body@test.com", PASSWORD));

        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", cookie.getValue())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshForDisabledUserIsRejected() throws Exception {
        User user = createUser("disabled-later@test.com", true);
        Cookie cookie = refreshCookie(loginResponse("disabled-later@test.com", PASSWORD));
        user.setEnabled(false);
        users.save(user);

        mockMvc.perform(refresh(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        createUser("logout@test.com", true);
        Cookie cookie = refreshCookie(loginResponse("logout@test.com", PASSWORD));

        mockMvc.perform(post("/api/v1/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value(REFRESH_COOKIE, ""))
                .andExpect(cookie().maxAge(REFRESH_COOKIE, 0))
                .andExpect(cookie().path(REFRESH_COOKIE, "/api/v1/auth"));

        mockMvc.perform(refresh(cookie)).andExpect(status().isUnauthorized());
        // Unknown and missing tokens are ignored
        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(REFRESH_COOKIE, "unknown")))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(REFRESH_COOKIE, 0));
    }

    @Test
    void anExpiredOrInvalidAccessTokenIsIgnoredByTheAuthEndpoints() throws Exception {
        createUser("stale@test.com", true);
        // Signed in an hour ago, so the access token (15 minutes) has expired
        TestClock.INSTANCE.set(Instant.now().minus(Duration.ofHours(1)));
        MockHttpServletResponse login = loginResponse("stale@test.com", PASSWORD);
        TestClock.INSTANCE.reset();
        String expired = bearer(tokens(login));
        Cookie cookie = refreshCookie(login);
        mockMvc.perform(get("/api/v1/me").header("Authorization", expired)).andExpect(status().isUnauthorized());

        MockHttpServletResponse refreshed = mockMvc.perform(refresh(cookie).header("Authorization", expired))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie rotated = refreshCookie(refreshed);
        assertThat(rotated.getValue()).isNotEqualTo(cookie.getValue());

        mockMvc.perform(post("/api/v1/auth/logout").cookie(rotated).header("Authorization", "Bearer garbage"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(REFRESH_COOKIE, 0));
        mockMvc.perform(refresh(rotated)).andExpect(status().isUnauthorized());
        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "stale@test.com", "password", PASSWORD))
                        .header("Authorization", expired))
                .andExpect(status().isOk());
    }

    // password reset

    @Test
    void passwordResetFlow() throws Exception {
        createUser("reset@test.com", false);
        IssuedTokens oldSession = loginBypassingVerification("reset@test.com");

        mockMvc.perform(postJson("/api/v1/auth/forgot-password", Map.of("email", "reset@test.com")))
                .andExpect(status().isAccepted());
        SimpleMailMessage mail = lastMail();
        assertThat(mail.getSubject()).isEqualTo("Password reset");
        assertThat(mail.getText()).contains("/reset-password#token=", "60 minutes");

        mockMvc.perform(postJson(
                        "/api/v1/auth/reset-password",
                        Map.of("token", tokenFromLastMail(), "newPassword", "newPassword123")))
                .andExpect(status().isNoContent());

        User user = users.findByEmail("reset@test.com").orElseThrow();
        assertThat(passwordEncoder.matches("newPassword123", user.getPasswordHash()))
                .isTrue();
        // Following the link proves ownership of the email address
        assertThat(user.isEmailVerified()).isTrue();
        // Existing sessions are ended, including the access tokens
        mockMvc.perform(refresh(new Cookie(REFRESH_COOKIE, oldSession.refreshToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + oldSession.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    /** Issues tokens directly, since an unverified user cannot log in. */
    private IssuedTokens loginBypassingVerification(String email) {
        return tokenService.issueTokens(users.findByEmail(email).orElseThrow());
    }

    @Test
    void forgotPasswordForUnknownEmailSendsNothing() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/forgot-password", Map.of("email", "nobody@test.com")))
                .andExpect(status().isAccepted());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void resetPasswordWithInvalidToken() throws Exception {
        mockMvc.perform(postJson(
                        "/api/v1/auth/reset-password", Map.of("token", "unknown", "newPassword", "newPassword123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void verificationTokenCannotBeUsedForPasswordReset() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/register", registration("mixed@test.com")));

        mockMvc.perform(postJson(
                        "/api/v1/auth/reset-password",
                        Map.of("token", tokenFromLastMail(), "newPassword", "newPassword123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        assertThat(refreshTokens.count()).isZero();
    }
}
