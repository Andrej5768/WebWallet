package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.AbstractIntegrationTest;
import com.andrewCorp.webWallet.persistence.dao.PasswordResetTokenRepository;
import com.andrewCorp.webWallet.persistence.dao.VerificationTokenRepository;
import com.andrewCorp.webWallet.persistence.model.PasswordResetToken;
import com.andrewCorp.webWallet.persistence.model.User;
import com.andrewCorp.webWallet.persistence.model.VerificationToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class OldRegistrationControllerTest extends AbstractIntegrationTest {

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void showRegistrationForm() throws Exception {
        mockMvc.perform(get("/old/user/registration"))
                .andExpect(status().isOk())
                .andExpect(view().name("registration"))
                .andExpect(model().attributeExists("user"));
    }

    @Test
    void registerUserAccount() throws Exception {
        mockMvc.perform(post("/old/user/registration")
                        .param("firstName", "John")
                        .param("lastName", "Doe")
                        .param("email", "old-john@test.com")
                        .param("password", PASSWORD)
                        .param("matchingPassword", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(view().name("successRegister"));

        assertThat(userService.findUserByEmail("old-john@test.com")).isNotNull();
    }

    @Test
    void registerUserAccountWithExistingEmail() throws Exception {
        registerUser("old-taken@test.com");

        mockMvc.perform(post("/old/user/registration")
                        .param("firstName", "John")
                        .param("lastName", "Doe")
                        .param("email", "old-taken@test.com")
                        .param("password", PASSWORD)
                        .param("matchingPassword", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(view().name("registration"))
                .andExpect(model().attributeExists("message"));
    }

    @Test
    void confirmRegistrationWithValidToken() throws Exception {
        User user = registerUser();
        user.setEnabled(false);
        userService.createVerificationTokenForUser(user, "old-verify");

        mockMvc.perform(get("/old/registrationConfirm").with(as(user)).param("token", "old-verify"))
                .andExpect(redirectedUrl("/login.html?lang=en"));

        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void confirmRegistrationWithUnknownToken() throws Exception {
        mockMvc.perform(get("/old/registrationConfirm").with(as(registerUser())).param("token", "unknown"))
                .andExpect(redirectedUrl("/badUser.html?lang=en"));
    }

    @Test
    void confirmRegistrationWithExpiredToken() throws Exception {
        User user = registerUser();
        VerificationToken token = new VerificationToken("old-expired", user);
        token.setExpiryDate(new Date(System.currentTimeMillis() - 1000));
        verificationTokenRepository.save(token);

        mockMvc.perform(get("/old/registrationConfirm").with(as(user)).param("token", "old-expired"))
                .andExpect(redirectedUrl("/badUser.html?lang=en"));
    }

    @Test
    void resendRegistrationToken() throws Exception {
        User user = registerUser();
        userService.createVerificationTokenForUser(user, "old-resend");

        mockMvc.perform(get("/old/user/resendRegistrationToken").with(as(user)).param("token", "old-resend"))
                .andExpect(redirectedUrl("/login.html?lang=en"));

        assertThat(verificationTokenRepository.findByToken("old-resend")).isNull();
    }

    @Test
    void resetPasswordForExistingUser() throws Exception {
        User user = registerUser();

        mockMvc.perform(post("/old/user/resetPassword").with(as(user)).param("email", user.getEmail()))
                .andExpect(redirectedUrl("/login.html?lang=en"));

        assertThat(passwordResetTokenRepository.findByUser(user)).isNotNull();
    }

    @Test
    void resetPasswordForUnknownUser() throws Exception {
        mockMvc.perform(post("/old/user/resetPassword").with(as(registerUser())).param("email", "nobody@test.com"))
                .andExpect(redirectedUrl("/login.html?lang=en"));
    }

    @Test
    void changePasswordWithValidToken() throws Exception {
        User user = registerUser();
        userService.createPasswordResetTokenForUser(user, "old-reset");

        mockMvc.perform(get("/old/user/changePassword").with(as(user))
                        .param("id", user.getId().toString())
                        .param("token", "old-reset"))
                .andExpect(redirectedUrl("/updatePassword.html?lang=en"));
    }

    @Test
    void changePasswordWithTokenOfAnotherUser() throws Exception {
        User user = registerUser();
        userService.createPasswordResetTokenForUser(user, "old-reset");

        mockMvc.perform(get("/old/user/changePassword").with(as(user))
                        .param("id", String.valueOf(user.getId() + 1000))
                        .param("token", "old-reset"))
                .andExpect(redirectedUrl("/login.html?lang=en"));
    }

    @Test
    void changePasswordWithExpiredToken() throws Exception {
        User user = registerUser();
        PasswordResetToken token = new PasswordResetToken("old-expired-reset", user);
        token.setExpiryDate(new Date(System.currentTimeMillis() - 1000));
        passwordResetTokenRepository.save(token);

        mockMvc.perform(get("/old/user/changePassword").with(as(user))
                        .param("id", user.getId().toString())
                        .param("token", "old-expired-reset"))
                .andExpect(redirectedUrl("/login.html?lang=en"));
    }

    @Test
    void savePasswordChangesPasswordOfCurrentUser() throws Exception {
        User user = registerUser();

        mockMvc.perform(post("/old/user/savePassword").with(as(user)).param("password", "newPassword123"))
                .andExpect(status().is3xxRedirection());

        assertThat(passwordEncoder.matches("newPassword123", user.getPassword())).isTrue();
    }
}
