package com.andrewCorp.webWallet.security;

import com.andrewCorp.webWallet.persistence.model.User;
import com.andrewCorp.webWallet.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.WebAttributes;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuthenticationSuccessHandlersTest {

    private final ActiveUserStore activeUserStore = new ActiveUserStore();
    private final DeviceService deviceService = mock(DeviceService.class);
    private final MockEnvironment environment = new MockEnvironment();

    private MySimpleUrlAuthenticationSuccessHandler simpleUrlHandler;
    private MyCustomLoginAuthenticationSuccessHandler customLoginHandler;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private User user;

    @BeforeEach
    void setUp() {
        simpleUrlHandler = new MySimpleUrlAuthenticationSuccessHandler();
        ReflectionTestUtils.setField(simpleUrlHandler, "activeUserStore", activeUserStore);
        ReflectionTestUtils.setField(simpleUrlHandler, "deviceService", deviceService);
        ReflectionTestUtils.setField(simpleUrlHandler, "env", environment);

        customLoginHandler = new MyCustomLoginAuthenticationSuccessHandler();
        ReflectionTestUtils.setField(customLoginHandler, "activeUserStore", activeUserStore);

        request = new MockHttpServletRequest();
        request.getSession().setAttribute(WebAttributes.AUTHENTICATION_EXCEPTION, "previous error");
        response = new MockHttpServletResponse();

        user = new User();
        user.setEmail("user@test.com");
        user.setFirstName("John");
    }

    private Authentication authentication(Object principal, String... authorities) {
        return new UsernamePasswordAuthenticationToken(principal, null, AuthorityUtils.createAuthorityList(authorities));
    }

    // MySimpleUrlAuthenticationSuccessHandler

    @Test
    void userIsRedirectedToHomepage() throws IOException {
        simpleUrlHandler.onAuthenticationSuccess(request, response, authentication(user, "READ_PRIVILEGE"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/homepage.html?user=user@test.com");
        assertThat(request.getSession().getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION)).isNull();
        assertThat(request.getSession().getMaxInactiveInterval()).isEqualTo(30 * 60);
        assertThat(activeUserStore.getUsers()).containsExactly("user@test.com");
        verify(deviceService, never()).verifyDevice(any(), any());
    }

    @Test
    void adminIsRedirectedToConsole() throws IOException {
        simpleUrlHandler.onAuthenticationSuccess(request, response, authentication(user, "READ_PRIVILEGE", "WRITE_PRIVILEGE"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/console");
    }

    @Test
    void nonPersistenceUserPrincipalUsesAuthenticationName() throws IOException {
        simpleUrlHandler.onAuthenticationSuccess(request, response, authentication("plain-name", "READ_PRIVILEGE"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/homepage.html?user=plain-name");
        assertThat(activeUserStore.getUsers()).containsExactly("plain-name");
    }

    @Test
    void userWithoutKnownPrivilegesIsRejected() {
        assertThatThrownBy(() -> simpleUrlHandler.onAuthenticationSuccess(request, response, authentication(user, "OTHER")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void committedResponseIsNotRedirected() throws IOException {
        response.setCommitted(true);

        simpleUrlHandler.onAuthenticationSuccess(request, response, authentication(user, "READ_PRIVILEGE"));

        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void deviceIsVerifiedWhenGeoIpLibIsEnabled() throws IOException {
        environment.setProperty("geo.ip.lib.enabled", "true");

        simpleUrlHandler.onAuthenticationSuccess(request, response, authentication(user, "READ_PRIVILEGE"));

        verify(deviceService).verifyDevice(user, request);
    }

    @Test
    void deviceVerificationErrorIsPropagated() throws IOException {
        environment.setProperty("geo.ip.lib.enabled", "true");
        doThrow(new IOException("geo db missing")).when(deviceService).verifyDevice(any(), any());

        assertThatThrownBy(() -> simpleUrlHandler.onAuthenticationSuccess(request, response, authentication(user, "READ_PRIVILEGE")))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void redirectStrategyCanBeReplaced() {
        DefaultRedirectStrategy strategy = new DefaultRedirectStrategy();
        simpleUrlHandler.setRedirectStrategy(strategy);

        assertThat(simpleUrlHandler.getRedirectStrategy()).isSameAs(strategy);
    }

    // MyCustomLoginAuthenticationSuccessHandler

    @Test
    void customLoginHandlerSetsWelcomeCookieAndRedirects() throws IOException {
        customLoginHandler.onAuthenticationSuccess(request, response, authentication(user, "READ_PRIVILEGE"));

        assertThat(response.getCookie("welcome").getValue()).isEqualTo("John");
        assertThat(response.getRedirectedUrl()).startsWith("/homepage.html?user=");
        assertThat(request.getSession().getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION)).isNull();
        assertThat(activeUserStore.getUsers()).containsExactly("user@test.com");
    }

    @Test
    void customLoginHandlerWithoutSessionStillRedirects() throws IOException {
        MockHttpServletRequest requestWithoutSession = new MockHttpServletRequest();

        customLoginHandler.onAuthenticationSuccess(requestWithoutSession, response, authentication(user, "READ_PRIVILEGE"));

        assertThat(response.getRedirectedUrl()).startsWith("/homepage.html?user=");
        assertThat(activeUserStore.getUsers()).isEmpty();
    }

    @Test
    void customLoginHandlerRedirectStrategyCanBeReplaced() {
        DefaultRedirectStrategy strategy = new DefaultRedirectStrategy();
        customLoginHandler.setRedirectStrategy(strategy);

        assertThat(customLoginHandler.getRedirectStrategy()).isSameAs(strategy);
    }
}
