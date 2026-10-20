package com.andrewcorp.webwallet.auth.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.auth.twofactor.Totp;
import com.andrewcorp.webwallet.common.crypto.SecretCipher;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.user.User;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;

@TestPropertySource(properties = {"app.google.client-id=test-client", "app.google.client-secret=test-secret"})
class GoogleSignInTest extends AbstractIntegrationTest {

    private static final String REDIRECT_URI = "http://localhost:5173/api/v1/auth/google/callback";

    @MockitoBean
    private GoogleClient google;

    @Autowired
    private SecretCipher cipher;

    @BeforeEach
    void googlePage() {
        when(google.authorizationUrl(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> "https://accounts.google.com/o/oauth2/v2/auth?state=" + call.getArgument(0));
    }

    private record Started(String state, Cookie cookie) {}

    private static Started started(MockHttpServletResponse response) {
        String state = UriComponentsBuilder.fromUriString(response.getRedirectedUrl())
                .build()
                .getQueryParams()
                .getFirst("state");
        Cookie cookie = response.getCookie(GoogleStateCookie.NAME);
        assertThat(cookie).isNotNull();
        return new Started(state, new Cookie(GoogleStateCookie.NAME, cookie.getValue()));
    }

    private Started startSignIn() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/auth/google/start"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse();
        return started(response);
    }

    /** Google redirects back with the code; returns the page of the web client the callback sends to. */
    private String callback(Started started, String code) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/auth/google/callback")
                        .param("state", started.state())
                        .param("code", code)
                        .cookie(started.cookie()))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse();
        assertThat(response.getRedirectedUrl()).startsWith("http://localhost:5173/");
        return response.getRedirectedUrl().substring("http://localhost:5173".length());
    }

    private void googleReturns(GoogleIdentity identity) {
        when(google.exchange(eq("code-1"), anyString(), eq(REDIRECT_URI), anyString()))
                .thenReturn(identity);
    }

    private static String ticket(String page) {
        assertThat(page).startsWith("/auth/google#ticket=");
        return page.substring("/auth/google#ticket=".length());
    }

    private MockHttpServletResponse complete(String ticket) throws Exception {
        return mockMvc.perform(postJson("/api/v1/auth/google/complete", Map.of("ticket", ticket)))
                .andReturn()
                .getResponse();
    }

    @Test
    void tellsTheClientThatGoogleSignInIsOn() throws Exception {
        mockMvc.perform(get("/api/v1/auth/options"))
                .andExpect(jsonPath("$.googleSignIn").value(true));
    }

    @Test
    void createsAnAccountWithoutAPassword() throws Exception {
        googleReturns(new GoogleIdentity("sub-1", "Olena@Gmail.com", true, "Олена", "Коваль"));
        Started started = startSignIn();
        assertThat(started.cookie().getValue()).isEqualTo(started.state());

        String page = callback(started, "code-1");
        MockHttpServletResponse response = complete(ticket(page));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tokens(response).accessToken()).isNotBlank();
        mockMvc.perform(refresh(refreshCookie(response))).andExpect(status().isOk());
        User user = users.findByEmail("olena@gmail.com").orElseThrow();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.hasPassword()).isFalse();
        assertThat(user.getFirstName()).isEqualTo("Олена");
        assertThat(user.getGoogleSubject()).isEqualTo("sub-1");
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens(response))))
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.googleEmail").value("Olena@Gmail.com"));
        mockMvc.perform(get("/api/v1/categories").header("Authorization", bearer(tokens(response))))
                .andExpect(jsonPath("$.length()").value(greaterThan(0)));

        // The ticket works once, and the password does not sign in to an account without one
        assertThat(complete(ticket(page)).getStatus()).isEqualTo(400);
        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "olena@gmail.com", "password", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signsInToTheLinkedAccountAgain() throws Exception {
        googleReturns(new GoogleIdentity("sub-1", "olena@gmail.com", true, "Олена", null));
        complete(ticket(callback(startSignIn(), "code-1")));

        // The email of the Google account changed; the account id is what counts
        googleReturns(new GoogleIdentity("sub-1", "olena.new@gmail.com", true, "Олена", null));
        assertThat(complete(ticket(callback(startSignIn(), "code-1"))).getStatus())
                .isEqualTo(200);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void linksAnAccountWithTheSameVerifiedEmail() throws Exception {
        User existing = createUser("olena@gmail.com", true);
        googleReturns(new GoogleIdentity("sub-1", "olena@gmail.com", true, "Olena", null));

        assertThat(complete(ticket(callback(startSignIn(), "code-1"))).getStatus())
                .isEqualTo(200);

        User linked = users.findById(existing.getId()).orElseThrow();
        assertThat(linked.getGoogleSubject()).isEqualTo("sub-1");
        assertThat(linked.hasPassword()).isTrue();
    }

    @Test
    void doesNotLinkAnUnverifiedAccountOrAnUnverifiedGoogleEmail() throws Exception {
        createUser("olena@gmail.com", false);
        googleReturns(new GoogleIdentity("sub-1", "olena@gmail.com", true, "Olena", null));
        assertThat(callback(startSignIn(), "code-1")).isEqualTo("/auth/google#error=email_taken");

        googleReturns(new GoogleIdentity("sub-2", "other@gmail.com", false, "Other", null));
        assertThat(callback(startSignIn(), "code-1")).isEqualTo("/auth/google#error=unverified");
        assertThat(users.findByEmail("other@gmail.com")).isEmpty();
    }

    @Test
    void asksForTheTwoFactorCodeAfterGoogle() throws Exception {
        User user = createVerifiedUser();
        user.setTotpSecret(cipher.encrypt(Totp.newSecret()), Instant.now());
        users.save(user);
        googleReturns(new GoogleIdentity("sub-1", user.getEmail(), true, "John", null));

        MockHttpServletResponse response = complete(ticket(callback(startSignIn(), "code-1")));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getCookie(REFRESH_COOKIE)).isNull();
        assertThat(response.getContentAsString()).contains("twoFactorChallenge");
    }

    @Test
    void rejectsACallbackWithoutTheCookieOfTheBrowserThatStarted() throws Exception {
        Started started = startSignIn();

        String page = callback(new Started(started.state(), new Cookie(GoogleStateCookie.NAME, "other")), "code-1");

        assertThat(page).isEqualTo("/auth/google#error=failed");
        verify(google, never()).exchange(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void reportsACancelledOrFailedSignIn() throws Exception {
        Started started = startSignIn();
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/auth/google/callback")
                        .param("state", started.state())
                        .param("error", "access_denied")
                        .cookie(started.cookie()))
                .andReturn()
                .getResponse();
        assertThat(response.getRedirectedUrl()).endsWith("/auth/google#error=cancelled");
        // The state is used up
        assertThat(callback(started, "code-1")).isEqualTo("/auth/google#error=failed");

        when(google.exchange(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE));
        assertThat(callback(startSignIn(), "code-1")).isEqualTo("/auth/google#error=failed");
    }

    @Test
    void linksAndUnlinksGoogleInTheSettings() throws Exception {
        User user = createVerifiedUser();
        String auth = bearer(login(user.getEmail(), PASSWORD));
        MockHttpServletResponse linkResponse = mockMvc.perform(
                        post("/api/v1/me/google/link").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Map<String, Object> body = jsonMapper.readValue(linkResponse.getContentAsString(), Map.class);
        String state = UriComponentsBuilder.fromUriString((String) body.get("url"))
                .build()
                .getQueryParams()
                .getFirst("state");
        Started started = new Started(
                state,
                new Cookie(
                        GoogleStateCookie.NAME,
                        linkResponse.getCookie(GoogleStateCookie.NAME).getValue()));
        googleReturns(new GoogleIdentity("sub-9", "john.personal@gmail.com", true, "John", null));

        assertThat(callback(started, "code-1")).isEqualTo("/settings?tab=security#google=linked");
        mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
                .andExpect(jsonPath("$.googleEmail").value("john.personal@gmail.com"));

        mockMvc.perform(post("/api/v1/me/google/unlink").header("Authorization", auth))
                .andExpect(status().isNoContent());
        assertThat(users.findById(user.getId()).orElseThrow().getGoogleSubject())
                .isNull();
    }

    @Test
    void anAccountWithoutAPasswordKeepsGoogleAndCanSetAPasswordByEmail() throws Exception {
        googleReturns(new GoogleIdentity("sub-1", "olena@gmail.com", true, "Олена", null));
        String auth = bearer(tokens(complete(ticket(callback(startSignIn(), "code-1")))));

        mockMvc.perform(post("/api/v1/me/google/unlink").header("Authorization", auth))
                .andExpect(status().isConflict());
        mockMvc.perform(postJson(
                        "/api/v1/me/password",
                        Map.of("currentPassword", "anything", "newPassword", "new-password"),
                        auth))
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));

        mockMvc.perform(postJson("/api/v1/auth/forgot-password", Map.of("email", "olena@gmail.com")))
                .andExpect(status().isAccepted());
        mockMvc.perform(postJson(
                        "/api/v1/auth/reset-password",
                        Map.of("token", tokenFromLastMail(), "newPassword", "new-password")))
                .andExpect(status().isNoContent());
        assertThat(login("olena@gmail.com", "new-password").accessToken()).isNotBlank();
    }
}
