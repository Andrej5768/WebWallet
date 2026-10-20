package com.andrewcorp.webwallet.auth.twofactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.user.User;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class TwoFactorTest extends AbstractIntegrationTest {

    private User user;
    private String auth;

    @BeforeEach
    void signIn() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
    }

    /** Turns two-factor authentication on and returns the secret and the recovery codes. */
    private Enabled enable() throws Exception {
        Map<String, Object> setup = json(postJson("/api/v1/me/two-factor/setup", Map.of(), auth), 200);
        String secret = (String) setup.get("secret");
        assertThat((String) setup.get("otpauthUri")).startsWith("otpauth://totp/WebWallet:");
        Map<String, Object> enabled =
                json(postJson("/api/v1/me/two-factor/enable", Map.of("code", code(secret, 0)), auth), 200);
        @SuppressWarnings("unchecked")
        List<String> recoveryCodes = (List<String>) enabled.get("recoveryCodes");
        return new Enabled(secret, recoveryCodes);
    }

    private record Enabled(String secret, List<String> recoveryCodes) {}

    /** The code of the step {@code offset} steps from now. */
    private static String code(String secret, int offset) {
        return Totp.code(Totp.unbase32(secret), Totp.step(TestClock.INSTANCE.instant()) + offset);
    }

    private String challenge() throws Exception {
        Map<String, Object> response =
                json(postJson("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", PASSWORD)), 200);
        assertThat(response.get("accessToken")).isNull();
        return (String) response.get("twoFactorChallenge");
    }

    private MockHttpServletResponse completeLogin(String challenge, String code, boolean trust) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("challenge", challenge);
        body.put("code", code);
        body.put("trustDevice", trust);
        return mockMvc.perform(postJson("/api/v1/auth/login/two-factor", body))
                .andReturn()
                .getResponse();
    }

    @Test
    void setupBecomesActiveOnlyWithACodeOfTheNewSecret() throws Exception {
        Map<String, Object> setup = json(postJson("/api/v1/me/two-factor/setup", Map.of(), auth), 200);

        mockMvc.perform(postJson("/api/v1/me/two-factor/enable", Map.of("code", "000000"), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TWO_FACTOR_CODE"));
        mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
                .andExpect(jsonPath("$.twoFactorEnabled").value(false));
        // Not on yet: the password alone still signs in
        assertThat(login(user.getEmail(), PASSWORD).accessToken()).isNotBlank();

        String secret = (String) setup.get("secret");
        Map<String, Object> enabled =
                json(postJson("/api/v1/me/two-factor/enable", Map.of("code", code(secret, 0)), auth), 200);

        assertThat((List<?>) enabled.get("recoveryCodes")).hasSize(10);
        mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
                .andExpect(jsonPath("$.twoFactorEnabled").value(true))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(10));
        mockMvc.perform(postJson("/api/v1/me/two-factor/setup", Map.of(), auth)).andExpect(status().isConflict());
    }

    @Test
    void signingInNeedsTheCodeAfterThePassword() throws Exception {
        Enabled enabled = enable();
        String challenge = challenge();

        MockHttpServletResponse wrong = completeLogin(challenge, "000000", false);
        assertThat(wrong.getStatus()).isEqualTo(400);
        assertThat(wrong.getCookie(REFRESH_COOKIE)).isNull();

        // A wrong code keeps the challenge; the code of the next step is fine, the one used to enable is not
        assertThat(completeLogin(challenge, code(enabled.secret(), 0), false).getStatus())
                .isEqualTo(400);
        MockHttpServletResponse ok = completeLogin(challenge, code(enabled.secret(), 1), false);
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(tokens(ok).accessToken()).isNotBlank();
        assertThat(ok.getCookie(TrustedDeviceCookie.NAME)).isNull();
        mockMvc.perform(refresh(refreshCookie(ok))).andExpect(status().isOk());

        // The challenge works once
        assertThat(completeLogin(challenge, enabled.recoveryCodes().getFirst(), false)
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    void theChallengeExpires() throws Exception {
        Enabled enabled = enable();
        String challenge = challenge();

        TestClock.INSTANCE.set(TestClock.INSTANCE.instant().plus(Duration.ofMinutes(6)));

        MockHttpServletResponse response = completeLogin(challenge, code(enabled.secret(), 0), false);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TWO_FACTOR_EXPIRED");
    }

    @Test
    void aRecoveryCodeSignsInOnce() throws Exception {
        Enabled enabled = enable();
        String recovery = enabled.recoveryCodes().get(3);

        // Typed in lower case and without the dash
        MockHttpServletResponse ok =
                completeLogin(challenge(), recovery.toLowerCase().replace("-", ""), false);
        assertThat(ok.getStatus()).isEqualTo(200);

        assertThat(completeLogin(challenge(), recovery, false).getStatus()).isEqualTo(400);
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens(ok))))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(9));
    }

    @Test
    void aTrustedBrowserSkipsTheCodeUntilThePasswordChanges() throws Exception {
        Enabled enabled = enable();

        MockHttpServletResponse ok = completeLogin(challenge(), code(enabled.secret(), 1), true);
        Cookie device = ok.getCookie(TrustedDeviceCookie.NAME);
        assertThat(device).isNotNull();
        assertThat(device.isHttpOnly()).isTrue();
        assertThat(device.getPath()).isEqualTo("/api/v1/auth");
        assertThat(device.getMaxAge()).isEqualTo(Duration.ofDays(90).toSeconds());

        Cookie sent = new Cookie(TrustedDeviceCookie.NAME, device.getValue());
        Map<String, String> credentials = Map.of("email", user.getEmail(), "password", PASSWORD);
        mockMvc.perform(postJson("/api/v1/auth/login", credentials).cookie(sent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.twoFactorChallenge").doesNotExist())
                .andExpect(cookie().exists(REFRESH_COOKIE));

        // The device of another user does not count
        User other = createVerifiedUser();
        String otherAuth = bearer(login(other.getEmail(), PASSWORD));
        Map<String, Object> setup = json(postJson("/api/v1/me/two-factor/setup", Map.of(), otherAuth), 200);
        json(
                postJson(
                        "/api/v1/me/two-factor/enable",
                        Map.of("code", code((String) setup.get("secret"), 0)),
                        otherAuth),
                200);
        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", other.getEmail(), "password", PASSWORD))
                        .cookie(sent))
                .andExpect(jsonPath("$.twoFactorChallenge").isNotEmpty());

        String session = bearer(tokens(ok));
        mockMvc.perform(postJson(
                        "/api/v1/me/password",
                        Map.of("currentPassword", PASSWORD, "newPassword", "another-password"),
                        session))
                .andExpect(status().isOk());

        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", "another-password"))
                        .cookie(sent))
                .andExpect(jsonPath("$.twoFactorChallenge").isNotEmpty());
    }

    @Test
    void disablingNeedsThePasswordAndACode() throws Exception {
        Enabled enabled = enable();

        mockMvc.perform(postJson(
                        "/api/v1/me/two-factor/disable",
                        Map.of("password", "wrong-password", "code", code(enabled.secret(), 1)),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        mockMvc.perform(postJson("/api/v1/me/two-factor/disable", Map.of("password", PASSWORD, "code", "000000"), auth))
                .andExpect(jsonPath("$.code").value("INVALID_TWO_FACTOR_CODE"));

        mockMvc.perform(postJson(
                        "/api/v1/me/two-factor/disable",
                        Map.of(
                                "password",
                                PASSWORD,
                                "code",
                                enabled.recoveryCodes().getFirst()),
                        auth))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
                .andExpect(jsonPath("$.twoFactorEnabled").value(false))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(0));
        assertThat(login(user.getEmail(), PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void newRecoveryCodesReplaceTheOldOnes() throws Exception {
        Enabled enabled = enable();

        mockMvc.perform(postJson(
                        "/api/v1/me/two-factor/recovery-codes",
                        Map.of("code", enabled.recoveryCodes().getFirst()),
                        auth))
                .andExpect(status().isBadRequest());
        Map<String, Object> fresh = json(
                postJson("/api/v1/me/two-factor/recovery-codes", Map.of("code", code(enabled.secret(), 1)), auth), 200);

        @SuppressWarnings("unchecked")
        List<String> freshCodes = (List<String>) fresh.get("recoveryCodes");
        assertThat(freshCodes).hasSize(10).doesNotContainAnyElementsOf(enabled.recoveryCodes());
        assertThat(completeLogin(challenge(), enabled.recoveryCodes().get(1), false)
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void wrongCodesRunOut() throws Exception {
        enable();
        String challenge = challenge();

        for (int i = 0; i < 10; i++) {
            completeLogin(challenge, "000000", false);
        }

        MockHttpServletResponse blocked = completeLogin(challenge, "000000", false);
        assertThat(blocked.getStatus()).isEqualTo(429);
    }
}
