package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.AbstractIntegrationTest;
import com.andrewCorp.webWallet.persistence.dao.PasswordResetTokenRepository;
import com.andrewCorp.webWallet.persistence.dao.VerificationTokenRepository;
import com.andrewCorp.webWallet.persistence.model.PasswordResetToken;
import com.andrewCorp.webWallet.persistence.model.User;
import com.andrewCorp.webWallet.persistence.model.VerificationToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Date;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class RegistrationControllerTest extends AbstractIntegrationTest {

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private MessageSource messages;

    private String message(String code) {
        return messages.getMessage(code, null, Locale.ENGLISH);
    }

    @Test
    void confirmRegistrationEnablesUserAndLogsIn() throws Exception {
        User user = registerUser();
        user.setEnabled(false);
        userService.createVerificationTokenForUser(user, "verify-token");

        MvcResult result = mockMvc.perform(get("/registrationConfirm").param("token", "verify-token"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/console")
                .contains("messageKey=message.accountVerified");
        assertThat(user.isEnabled()).isTrue();

        // The authentication must survive the redirect, i.e. be stored in the session
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getPrincipal()).isEqualTo(user);

        mockMvc.perform(get("/console").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("console"));
    }

    @Test
    void confirmRegistrationWithUnknownTokenRedirectsToBadUser() throws Exception {
        MvcResult result = mockMvc.perform(get("/registrationConfirm").param("token", "unknown"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/badUser")
                .contains("messageKey=auth.message.invalidToken")
                .contains("expired=false");
    }

    @Test
    void confirmRegistrationWithExpiredTokenRedirectsToBadUser() throws Exception {
        User user = registerUser();
        VerificationToken token = new VerificationToken("expired-token", user);
        token.setExpiryDate(new Date(System.currentTimeMillis() - 1000));
        verificationTokenRepository.save(token);

        MvcResult result = mockMvc.perform(get("/registrationConfirm").param("token", "expired-token"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/badUser")
                .contains("messageKey=auth.message.expired")
                .contains("expired=true")
                .contains("token=expired-token");
        assertThat(verificationTokenRepository.findByToken("expired-token")).isNull();
    }

    @Test
    void consoleShowsLocalizedMessage() throws Exception {
        mockMvc.perform(get("/console").with(as(registerUser())).param("messageKey", "message.accountVerified"))
                .andExpect(status().isOk())
                .andExpect(view().name("console"))
                .andExpect(model().attribute("message", message("message.accountVerified")));
    }

    @Test
    void badUserShowsMessageAndToken() throws Exception {
        mockMvc.perform(get("/badUser")
                        .param("messageKey", "auth.message.expired")
                        .param("expired", "true")
                        .param("token", "some-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("badUser"))
                .andExpect(model().attribute("message", message("auth.message.expired")))
                .andExpect(model().attribute("expired", "true"))
                .andExpect(model().attribute("token", "some-token"));
    }

    @Test
    void changePasswordWithValidTokenRedirectsToUpdatePassword() throws Exception {
        User user = registerUser();
        userService.createPasswordResetTokenForUser(user, "reset-token");

        MvcResult result = mockMvc.perform(get("/user/changePassword").param("token", "reset-token"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).startsWith("/updatePassword");
    }

    @Test
    void changePasswordWithExpiredTokenRedirectsToLogin() throws Exception {
        User user = registerUser();
        PasswordResetToken token = new PasswordResetToken("expired-token", user);
        token.setExpiryDate(new Date(System.currentTimeMillis() - 1000));
        passwordResetTokenRepository.save(token);

        MvcResult result = mockMvc.perform(get("/user/changePassword").param("token", "expired-token"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/login")
                .contains("messageKey=auth.message.expired");
    }

    @Test
    void changePasswordWithUnknownTokenRedirectsToLogin() throws Exception {
        MvcResult result = mockMvc.perform(get("/user/changePassword").param("token", "unknown"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/login")
                .contains("messageKey=auth.message.invalidToken");
    }

    @Test
    void updatePasswordPageShowsMessage() throws Exception {
        mockMvc.perform(get("/updatePassword").param("messageKey", "message.resetPasswordSuc"))
                .andExpect(status().isOk())
                .andExpect(view().name("updatePassword"))
                .andExpect(model().attribute("lang", "en"))
                .andExpect(model().attribute("message", message("message.resetPasswordSuc")));
    }

    @Test
    void loginPageShowsMessageAndError() throws Exception {
        mockMvc.perform(get("/login").param("messageKey", "message.badCredentials").param("error", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("message", message("message.badCredentials")))
                .andExpect(model().attribute("error", "true"));
    }

    @Test
    void loginPageWithoutParameters() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attributeDoesNotExist("message", "error"));
    }
}
