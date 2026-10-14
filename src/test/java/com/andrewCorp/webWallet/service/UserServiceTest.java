package com.andrewCorp.webWallet.service;

import com.andrewCorp.webWallet.AbstractIntegrationTest;
import com.andrewCorp.webWallet.persistence.dao.PasswordResetTokenRepository;
import com.andrewCorp.webWallet.persistence.dao.UserRepository;
import com.andrewCorp.webWallet.persistence.dao.VerificationTokenRepository;
import com.andrewCorp.webWallet.persistence.model.Category;
import com.andrewCorp.webWallet.persistence.model.User;
import com.andrewCorp.webWallet.persistence.model.VerificationToken;
import com.andrewCorp.webWallet.web.error.UserAlreadyExistException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.session.SessionRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserServiceTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Test
    void registerNewUserAccountCreatesWalletWithDefaultCategories() {
        User user = registerUser("new@test.com");

        assertThat(user.getId()).isNotNull();
        assertThat(user.getRoles()).extracting("name").containsExactly("ROLE_USER");
        assertThat(user.getWallet().getCategories())
                .extracting(Category::getName)
                .contains("Housing", "Transport", "Food", "Saving")
                .allSatisfy(name -> assertThat(name).isNotBlank());
        assertThat(user.getWallet().getCategories())
                .allSatisfy(category -> assertThat(category.getWallet()).isSameAs(user.getWallet()));
    }

    @Test
    void registerNewUserAccountRejectsDuplicateEmail() {
        registerUser("dup@test.com");

        assertThatThrownBy(() -> registerUser("dup@test.com"))
                .isInstanceOf(UserAlreadyExistException.class)
                .hasMessageContaining("dup@test.com");
    }

    @Test
    void verificationTokenLookups() {
        User user = registerUser();
        userService.createVerificationTokenForUser(user, "token-1");

        VerificationToken token = userService.getVerificationToken("token-1");
        assertThat(token.getUser()).isEqualTo(user);
        assertThat(userService.getUser("token-1")).isEqualTo(user);
        assertThat(userService.getUser("unknown")).isNull();
    }

    @Test
    void validateVerificationTokenEnablesUser() {
        User user = registerUser();
        user.setEnabled(false);
        userService.createVerificationTokenForUser(user, "token-2");

        assertThat(userService.validateVerificationToken("token-2")).isEqualTo(UserService.TOKEN_VALID);
        assertThat(user.isEnabled()).isTrue();
        assertThat(userService.validateVerificationToken("unknown")).isEqualTo(UserService.TOKEN_INVALID);
    }

    @Test
    void passwordResetTokenLookups() {
        User user = registerUser();
        userService.createPasswordResetTokenForUser(user, "reset-1");

        assertThat(userService.getPasswordResetToken("reset-1").getUser()).isEqualTo(user);
        assertThat(userService.getUserByPasswordResetToken("reset-1")).contains(user);
    }

    @Test
    void getUserById() {
        User user = registerUser();

        assertThat(userService.getUserByID(user.getId())).contains(user);
        assertThat(userService.getUserByID(-1)).isEmpty();
    }

    @Test
    void checkIfValidOldPassword() {
        User user = registerUser();

        assertThat(userService.checkIfValidOldPassword(user, PASSWORD)).isTrue();
        assertThat(userService.checkIfValidOldPassword(user, "wrong")).isFalse();
    }

    @Test
    void deleteUserRemovesTokens() {
        User user = registerUser();
        userService.createVerificationTokenForUser(user, "verify-del");
        userService.createPasswordResetTokenForUser(user, "reset-del");

        userService.deleteUser(user);

        assertThat(userRepository.findByEmail(user.getEmail())).isNull();
        assertThat(verificationTokenRepository.findByToken("verify-del")).isNull();
        assertThat(passwordResetTokenRepository.findByToken("reset-del")).isNull();
    }

    @Test
    void deleteUserWithoutTokens() {
        User user = registerUser();

        userService.deleteUser(user);

        assertThat(userRepository.findByEmail(user.getEmail())).isNull();
    }

    @Test
    void usersFromSessionRegistryOnlyIncludeActiveSessions() {
        User user = registerUser();
        sessionRegistry.registerNewSession("session-1", user);
        sessionRegistry.registerNewSession("session-2", "plain-principal");
        sessionRegistry.registerNewSession("session-3", "expired-principal");
        sessionRegistry.getSessionInformation("session-3").expireNow();
        try {
            assertThat(userService.getUsersFromSessionRegistry())
                    .contains(user.getEmail(), "plain-principal")
                    .doesNotContain("expired-principal");
        } finally {
            sessionRegistry.removeSessionInformation("session-1");
            sessionRegistry.removeSessionInformation("session-2");
            sessionRegistry.removeSessionInformation("session-3");
        }
    }
}
