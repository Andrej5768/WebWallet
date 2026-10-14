package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                .andExpect(jsonPath("$.detail").value("Потрібна автентифікація"));
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
        mockMvc.perform(put("/api/v1/me").header("Authorization", bearer(tokens))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("firstName", " Jane ", "lastName", "Smith", "locale", "uk"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.locale").value("uk"));

        User updated = users.findById(user.getId()).orElseThrow();
        assertThat(updated.getLastName()).isEqualTo("Smith");
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updated.getCreatedAt());
    }

    @Test
    void updateProfileRejectsUnsupportedLocale() throws Exception {
        mockMvc.perform(put("/api/v1/me").header("Authorization", bearer(tokens))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("firstName", "Jane", "lastName", "Smith", "locale", "de"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("locale"));
    }

    @Test
    void changePasswordEndsOtherSessionsAndReturnsNewTokens() throws Exception {
        TokenResponse otherSession = login(user.getEmail(), PASSWORD);

        String body = mockMvc.perform(postJson("/api/v1/me/password",
                        Map.of("currentPassword", PASSWORD, "newPassword", "newPassword123"))
                        .header("Authorization", bearer(tokens)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        TokenResponse newTokens = jsonMapper.readValue(body, TokenResponse.class);

        assertThat(passwordEncoder.matches("newPassword123", users.findById(user.getId()).orElseThrow().getPasswordHash()))
                .isTrue();
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", otherSession.refreshToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", newTokens.refreshToken())))
                .andExpect(status().isOk());
    }

    @Test
    void changePasswordWithWrongCurrentPassword() throws Exception {
        mockMvc.perform(postJson("/api/v1/me/password", Map.of("currentPassword", "wrong", "newPassword", "newPassword123"))
                        .header("Authorization", bearer(tokens)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
    }
}
