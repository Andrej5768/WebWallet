package com.andrewcorp.webwallet.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class UserControllerTest extends AbstractIntegrationTest {

    private User user;
    private TokenResponse tokens;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        tokens = login(user.getEmail(), PASSWORD);
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void meWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void meWithInvalidTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unauthorizedMessageIsLocalized() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Accept-Language", "uk"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Увійдіть, щоб продовжити"));
    }

    @Test
    void meOfDeletedUserIsUnauthorized() throws Exception {
        users.delete(user);

        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void updateProfile() throws Exception {
        mockMvc.perform(put("/api/v1/me")
                        .header("Authorization", bearer(tokens))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "firstName", " Jane ", "lastName", "Smith", "locale", "uk", "baseCurrency", "USD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.locale").value("uk"))
                .andExpect(jsonPath("$.baseCurrency").value("USD"));

        User updated = users.findById(user.getId()).orElseThrow();
        assertThat(updated.getLastName()).isEqualTo("Smith");
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updated.getCreatedAt());
    }

    @Test
    void lastNameCanBeRemoved() throws Exception {
        mockMvc.perform(put("/api/v1/me")
                        .header("Authorization", bearer(tokens))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("firstName", "Jane", "lastName", " ", "locale", "en", "baseCurrency", "UAH"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").doesNotExist());
        assertThat(users.findById(user.getId()).orElseThrow().getLastName()).isNull();

        mockMvc.perform(put("/api/v1/me")
                        .header("Authorization", bearer(tokens))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("firstName", "Jane", "locale", "en", "baseCurrency", "UAH"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens)))
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.lastName").doesNotExist());
    }

    @Test
    void updateProfileRejectsUnsupportedLocale() throws Exception {
        mockMvc.perform(put("/api/v1/me")
                        .header("Authorization", bearer(tokens))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "firstName", "Jane", "lastName", "Smith", "locale", "de", "baseCurrency", "UAH"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("locale"));
    }

    @Test
    void changePasswordEndsOtherSessionsAndReturnsNewTokens() throws Exception {
        MockHttpServletResponse otherLogin = loginResponse(user.getEmail(), PASSWORD);
        TokenResponse otherSession = tokens(otherLogin);

        MockHttpServletResponse response = mockMvc.perform(postJson(
                                "/api/v1/me/password",
                                Map.of("currentPassword", PASSWORD, "newPassword", "newPassword123"))
                        .header("Authorization", bearer(tokens)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn()
                .getResponse();
        TokenResponse newTokens = tokens(response);

        assertThat(passwordEncoder.matches(
                        "newPassword123",
                        users.findById(user.getId()).orElseThrow().getPasswordHash()))
                .isTrue();
        mockMvc.perform(refresh(refreshCookie(otherLogin))).andExpect(status().isUnauthorized());
        // Access tokens issued before the change stop working at once, not when they expire
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(otherSession)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(newTokens)))
                .andExpect(status().isOk());
        mockMvc.perform(refresh(refreshCookie(response))).andExpect(status().isOk());
    }

    @Test
    void changePasswordVoidsPasswordResetLinks() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/forgot-password", Map.of("email", user.getEmail())))
                .andExpect(status().isAccepted());
        String resetToken = tokenFromLastMail();

        mockMvc.perform(postJson(
                                "/api/v1/me/password",
                                Map.of("currentPassword", PASSWORD, "newPassword", "newPassword123"))
                        .header("Authorization", bearer(tokens)))
                .andExpect(status().isOk());

        mockMvc.perform(postJson(
                        "/api/v1/auth/reset-password", Map.of("token", resetToken, "newPassword", "otherPassword123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void disabledUserLosesAccessAtOnce() throws Exception {
        user.setEnabled(false);
        users.save(user);

        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePasswordWithWrongCurrentPassword() throws Exception {
        mockMvc.perform(postJson(
                                "/api/v1/me/password",
                                Map.of("currentPassword", "wrong", "newPassword", "newPassword123"))
                        .header("Authorization", bearer(tokens)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    void guessingTheCurrentPasswordIsLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(postJson(
                                    "/api/v1/me/password",
                                    Map.of("currentPassword", "wrong" + i, "newPassword", "newPassword123"))
                            .header("Authorization", bearer(tokens)))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(postJson(
                                "/api/v1/me/password",
                                Map.of("currentPassword", PASSWORD, "newPassword", "newPassword123"))
                        .header("Authorization", bearer(tokens)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void baseCurrencyCannotChangeWhileTheBudgetHasMoney() throws Exception {
        Long category = id(
                json(postJson("/api/v1/categories", Map.of("name", "Food", "kind", "EXPENSE"), bearer(tokens)), 201));
        mockMvc.perform(putJson(
                        "/api/v1/budget/2026-10/categories/" + category, Map.of("assigned", 100), bearer(tokens)))
                .andExpect(status().isOk());

        mockMvc.perform(putJson(
                        "/api/v1/me",
                        Map.of("firstName", "Jane", "lastName", "Smith", "locale", "en", "baseCurrency", "USD"),
                        bearer(tokens)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("baseCurrency"));
    }
}
