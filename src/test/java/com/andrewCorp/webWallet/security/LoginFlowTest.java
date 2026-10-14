package com.andrewCorp.webWallet.security;

import com.andrewCorp.webWallet.AbstractIntegrationTest;
import com.andrewCorp.webWallet.persistence.model.User;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.WebAttributes;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginFlowTest extends AbstractIntegrationTest {

    @Autowired
    private MessageSource messages;

    @Autowired
    private ActiveUserStore activeUserStore;

    private String message(String code) {
        return messages.getMessage(code, null, Locale.ENGLISH);
    }

    /** Every test logs in from its own IP so that LoginAttemptService counters do not interfere. */
    private final String ip = "10.0.0." + (UUID.randomUUID().hashCode() & 0xff);

    private RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MockHttpServletRequestBuilder login(String email, String password) {
        return post("/login")
                .param("username", email)
                .param("password", password)
                .with(fromIp(ip));
    }

    private Object authenticationError(MvcResult result) {
        return result.getRequest().getSession().getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
    }

    @Test
    void userIsRedirectedToHomepageAndRegisteredAsActive() throws Exception {
        User user = registerUser();

        MvcResult result = mockMvc.perform(login(user.getEmail(), PASSWORD))
                .andExpect(redirectedUrl("/homepage.html?user=" + user.getEmail()))
                .andReturn();

        Object loggedUser = result.getRequest().getSession().getAttribute("user");
        assertThat(loggedUser).isInstanceOf(LoggedUser.class);
        assertThat(((LoggedUser) loggedUser).getUsername()).isEqualTo(user.getEmail());
        assertThat(activeUserStore.getUsers()).contains(user.getEmail());
    }

    @Test
    void adminIsRedirectedToConsole() throws Exception {
        mockMvc.perform(login("test@test.com", "test"))
                .andExpect(redirectedUrl("/console"));
    }

    @Test
    void wrongPasswordShowsBadCredentialsMessage() throws Exception {
        User user = registerUser();

        MvcResult result = mockMvc.perform(login(user.getEmail(), "wrong-password"))
                .andExpect(redirectedUrl("/login?error=true"))
                .andReturn();

        assertThat(authenticationError(result)).isEqualTo(message("message.badCredentials"));
    }

    @Test
    void unknownUserShowsBadCredentialsMessage() throws Exception {
        MvcResult result = mockMvc.perform(login("nobody@test.com", PASSWORD))
                .andExpect(redirectedUrl("/login?error=true"))
                .andReturn();

        assertThat(authenticationError(result)).isEqualTo(message("message.badCredentials"));
    }

    @Test
    void disabledUserShowsDisabledMessage() throws Exception {
        User user = registerUser();
        user.setEnabled(false);

        MvcResult result = mockMvc.perform(login(user.getEmail(), PASSWORD))
                .andExpect(redirectedUrl("/login?error=true"))
                .andReturn();

        assertThat(authenticationError(result)).isEqualTo(message("auth.message.disabled"));
    }

    @Test
    void ipIsBlockedAfterTenFailedAttempts() throws Exception {
        User user = registerUser();

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login(user.getEmail(), "wrong-password"))
                    .andExpect(redirectedUrl("/login?error=true"));
        }

        // Even the correct password is rejected now
        MvcResult result = mockMvc.perform(login(user.getEmail(), PASSWORD))
                .andExpect(redirectedUrl("/login?error=true"))
                .andReturn();

        assertThat(authenticationError(result)).isEqualTo(message("auth.message.blocked"));
    }

    @Test
    void successfulLoginResetsFailedAttempts() throws Exception {
        User user = registerUser();

        for (int i = 0; i < 9; i++) {
            mockMvc.perform(login(user.getEmail(), "wrong-password"));
        }
        mockMvc.perform(login(user.getEmail(), PASSWORD))
                .andExpect(redirectedUrl("/homepage.html?user=" + user.getEmail()));
        mockMvc.perform(login(user.getEmail(), "wrong-password"));

        mockMvc.perform(login(user.getEmail(), PASSWORD))
                .andExpect(redirectedUrl("/homepage.html?user=" + user.getEmail()));
    }

    @Test
    void logoutRedirectsToLogoutPage() throws Exception {
        User user = registerUser();
        MockHttpSession session = (MockHttpSession) mockMvc.perform(login(user.getEmail(), PASSWORD))
                .andReturn().getRequest().getSession();

        mockMvc.perform(post("/logout").session(session))
                .andExpect(redirectedUrl("/logout.html?logSucc=true"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void rememberMeCookieAuthenticatesLaterRequests() throws Exception {
        User user = registerUser();

        Cookie rememberMe = mockMvc.perform(login(user.getEmail(), PASSWORD).param("remember-me", "on"))
                .andReturn().getResponse().getCookie("remember-me");
        assertThat(rememberMe).isNotNull();

        mockMvc.perform(get("/debug").cookie(rememberMe))
                .andExpect(status().isOk());
    }

    @Test
    void protectedPageWithoutAuthenticationRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/homepage"))
                .andExpect(redirectedUrl("/login"));
    }
}
