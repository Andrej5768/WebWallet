package com.andrewCorp.webWallet.security;

import com.andrewCorp.webWallet.persistence.model.User;
import com.andrewCorp.webWallet.registration.OnRegistrationCompleteEvent;
import jakarta.servlet.http.HttpSessionBindingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SecuritySupportTest {

    @Test
    void loginAttemptServiceBlocksAfterTenFailures() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < 9; i++) {
            service.loginFailed("1.1.1.1");
        }
        assertThat(service.isBlocked("1.1.1.1")).isFalse();

        service.loginFailed("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isTrue();
        assertThat(service.isBlocked("2.2.2.2")).isFalse();

        service.loginSucceeded("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void loggedUserTracksActiveUsersThroughSessionBinding() {
        ActiveUserStore store = new ActiveUserStore();
        MockHttpSession session = new MockHttpSession();
        LoggedUser loggedUser = new LoggedUser("user@test.com", store);

        session.setAttribute("user", loggedUser);
        session.setAttribute("user-again", loggedUser);
        assertThat(store.getUsers()).containsExactly("user@test.com");

        session.removeAttribute("user");
        assertThat(store.getUsers()).isEmpty();
    }

    @Test
    void loggedUserBindingEventUsesBoundValue() {
        ActiveUserStore store = new ActiveUserStore();
        store.setUsers(new ArrayList<>(List.of("existing@test.com")));
        LoggedUser loggedUser = new LoggedUser();
        loggedUser.setUsername("other@test.com");
        LoggedUser listener = new LoggedUser("listener@test.com", store);

        listener.valueBound(new HttpSessionBindingEvent(new MockHttpSession(), "user", loggedUser));

        assertThat(store.getUsers()).containsExactly("existing@test.com", "other@test.com");
        assertThat(loggedUser.getUsername()).isEqualTo("other@test.com");
    }

    @Test
    void customAuthenticationDetailsReadVerificationCode() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("code", "123456");

        CustomWebAuthenticationDetails details =
                (CustomWebAuthenticationDetails) new CustomWebAuthenticationDetailsSource().buildDetails(request);

        assertThat(details.getVerificationCode()).isEqualTo("123456");
    }

    @Test
    void registrationEventCarriesUserLocaleAndUrl() {
        User user = new User();
        OnRegistrationCompleteEvent event = new OnRegistrationCompleteEvent(user, Locale.ENGLISH, "http://localhost:8080");

        assertThat(event.getUser()).isSameAs(user);
        assertThat(event.getSource()).isSameAs(user);
        assertThat(event.getLocale()).isEqualTo(Locale.ENGLISH);
        assertThat(event.getAppUrl()).isEqualTo("http://localhost:8080");
    }
}
