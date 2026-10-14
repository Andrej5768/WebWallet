package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.AbstractIntegrationTest;
import com.andrewCorp.webWallet.persistence.dao.PasswordResetTokenRepository;
import com.andrewCorp.webWallet.persistence.dao.VerificationTokenRepository;
import com.andrewCorp.webWallet.persistence.model.PasswordResetToken;
import com.andrewCorp.webWallet.persistence.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationRestControllerTest extends AbstractIntegrationTest {

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MessageSource messages;

    private String message(String code) {
        return messages.getMessage(code, null, Locale.ENGLISH);
    }

    // registration

    @Test
    void registerCreatesUserWithWalletAndVerificationToken() throws Exception {
        mockMvc.perform(post("/user/registration")
                        .param("firstName", "John")
                        .param("lastName", "Doe")
                        .param("email", "john@test.com")
                        .param("password", PASSWORD)
                        .param("matchingPassword", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("success"));

        User user = userService.findUserByEmail("john@test.com");
        assertThat(user).isNotNull();
        assertThat(user.getWallet()).isNotNull();
        assertThat(user.getWallet().getCategories()).isNotEmpty();
        assertThat(passwordEncoder.matches(PASSWORD, user.getPassword())).isTrue();
        assertThat(verificationTokenRepository.findByUser(user)).isNotNull();
    }

    @Test
    void registerWithInvalidDataIsBadRequest() throws Exception {
        mockMvc.perform(post("/user/registration")
                        .param("firstName", "John")
                        .param("lastName", "Doe")
                        .param("email", "not-an-email")
                        .param("password", "short")
                        .param("matchingPassword", "different"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("InvaliduserDto"))
                .andExpect(jsonPath("$.message", containsString("email")));
    }

    @Test
    void registerWithExistingEmailIsConflict() throws Exception {
        registerUser("taken@test.com");

        mockMvc.perform(post("/user/registration")
                        .param("firstName", "John")
                        .param("lastName", "Doe")
                        .param("email", "taken@test.com")
                        .param("password", PASSWORD)
                        .param("matchingPassword", PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("UserAlreadyExist"))
                .andExpect(jsonPath("$.message").value(message("message.regError")));
    }

    @Test
    void resendRegistrationTokenReplacesToken() throws Exception {
        User user = registerUser();
        userService.createVerificationTokenForUser(user, "old-token");

        mockMvc.perform(get("/user/resendRegistrationToken").param("token", "old-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(message("message.resendToken")));

        assertThat(verificationTokenRepository.findByToken("old-token")).isNull();
        assertThat(verificationTokenRepository.findByUser(user).getToken()).isNotEqualTo("old-token");
    }

    // password reset

    @Test
    void resetPasswordCreatesTokenForExistingUser() throws Exception {
        User user = registerUser();

        mockMvc.perform(post("/user/resetPassword").param("email", user.getEmail()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(message("message.resetPasswordEmail")));

        assertThat(passwordResetTokenRepository.findByUser(user)).isNotNull();
    }

    @Test
    void resetPasswordForUnknownEmailRespondsTheSame() throws Exception {
        mockMvc.perform(post("/user/resetPassword").param("email", "nobody@test.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(message("message.resetPasswordEmail")));
    }

    @Test
    void savePasswordWithValidTokenChangesPassword() throws Exception {
        User user = registerUser();
        userService.createPasswordResetTokenForUser(user, "reset-token");

        mockMvc.perform(post("/user/savePassword")
                        .param("token", "reset-token")
                        .param("newPassword", "newPassword123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(message("message.resetPasswordSuc")));

        assertThat(passwordEncoder.matches("newPassword123", user.getPassword())).isTrue();
    }

    @Test
    void savePasswordWithUnknownTokenIsRejected() throws Exception {
        mockMvc.perform(post("/user/savePassword")
                        .param("token", "unknown")
                        .param("newPassword", "newPassword123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(message("auth.message.invalidToken")));
    }

    @Test
    void savePasswordWithExpiredTokenIsRejected() throws Exception {
        User user = registerUser();
        PasswordResetToken token = new PasswordResetToken("expired-token", user);
        token.setExpiryDate(new Date(System.currentTimeMillis() - 1000));
        passwordResetTokenRepository.save(token);

        mockMvc.perform(post("/user/savePassword")
                        .param("token", "expired-token")
                        .param("newPassword", "newPassword123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(message("auth.message.expired")));

        assertThat(passwordEncoder.matches(PASSWORD, user.getPassword())).isTrue();
    }

    @Test
    void savePasswordWithTooShortPasswordIsBadRequest() throws Exception {
        mockMvc.perform(post("/user/savePassword")
                        .param("token", "any")
                        .param("newPassword", "short"))
                .andExpect(status().isBadRequest());
    }

    // password change

    @Test
    void updatePasswordWithCorrectOldPassword() throws Exception {
        User user = registerUser();

        mockMvc.perform(post("/user/updatePassword").with(as(user))
                        .param("oldPassword", PASSWORD)
                        .param("newPassword", "newPassword123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(message("message.updatePasswordSuc")));

        assertThat(passwordEncoder.matches("newPassword123", user.getPassword())).isTrue();
    }

    @Test
    void updatePasswordWithWrongOldPasswordIsBadRequest() throws Exception {
        User user = registerUser();

        mockMvc.perform(post("/user/updatePassword").with(as(user))
                        .param("oldPassword", "wrong-password")
                        .param("newPassword", "newPassword123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("InvalidOldPassword"))
                .andExpect(jsonPath("$.message").value(message("message.invalidOldPassword")));

        assertThat(passwordEncoder.matches(PASSWORD, user.getPassword())).isTrue();
    }

    @Test
    void updatePasswordRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/user/updatePassword")
                        .param("oldPassword", PASSWORD)
                        .param("newPassword", "newPassword123"))
                .andExpect(status().is3xxRedirection());
    }

}
