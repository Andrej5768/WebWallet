package com.andrewcorp.webwallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.token.RefreshTokenRepository;
import com.andrewcorp.webwallet.user.Role;
import com.andrewcorp.webwallet.user.User;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
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
        assertThat(tokens.refreshToken()).isNotBlank();
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()));
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
    void accountIsBlockedAfterTooManyFailedLoginsFromDifferentIps() throws Exception {
        createUser("target@test.com", true);

        for (int i = 0; i < 10; i++) {
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
    void refreshRotatesTheRefreshToken() throws Exception {
        createUser("refresh@test.com", true);
        TokenResponse tokens = login("refresh@test.com", PASSWORD);

        String body = mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", tokens.refreshToken())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        TokenResponse refreshed = jsonMapper.readValue(body, TokenResponse.class);

        assertThat(refreshed.refreshToken()).isNotEqualTo(tokens.refreshToken());
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(refreshed)))
                .andExpect(status().isOk());
    }

    @Test
    void reusingRotatedRefreshTokenRevokesTheSession() throws Exception {
        createUser("reuse@test.com", true);
        TokenResponse tokens = login("reuse@test.com", PASSWORD);
        String body = mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", tokens.refreshToken())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        TokenResponse refreshed = jsonMapper.readValue(body, TokenResponse.class);

        // The old token is presented again, e.g. by an attacker who stole it
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", tokens.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // The legitimate newer token is revoked as well
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", refreshed.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithUnknownToken() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", "unknown")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshForDisabledUserIsRejected() throws Exception {
        User user = createUser("disabled-later@test.com", true);
        TokenResponse tokens = login("disabled-later@test.com", PASSWORD);
        user.setEnabled(false);
        users.save(user);

        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        createUser("logout@test.com", true);
        TokenResponse tokens = login("logout@test.com", PASSWORD);

        mockMvc.perform(postJson("/api/v1/auth/logout", Map.of("refreshToken", tokens.refreshToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
        // Unknown tokens are ignored
        mockMvc.perform(postJson("/api/v1/auth/logout", Map.of("refreshToken", "unknown")))
                .andExpect(status().isNoContent());
    }

    // password reset

    @Test
    void passwordResetFlow() throws Exception {
        createUser("reset@test.com", false);
        TokenResponse oldSession = loginBypassingVerification("reset@test.com");

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
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", oldSession.refreshToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(oldSession)))
                .andExpect(status().isUnauthorized());
    }

    /** Issues tokens directly, since an unverified user cannot log in. */
    private TokenResponse loginBypassingVerification(String email) {
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
