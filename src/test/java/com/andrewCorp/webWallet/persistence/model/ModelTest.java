package com.andrewCorp.webWallet.persistence.model;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelTest {

    private static User user(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    @Test
    void newUserIsEnabledByDefault() {
        assertThat(new User().isEnabled()).isTrue();
    }

    @Test
    void userEqualityIsBasedOnEmail() {
        User user = user("a@test.com");
        user.setFirstName("A");
        User sameEmail = user("a@test.com");
        sameEmail.setFirstName("B");

        assertThat(user).isEqualTo(sameEmail).hasSameHashCodeAs(sameEmail);
        assertThat(user).isNotEqualTo(user("b@test.com")).isNotEqualTo(null).isNotEqualTo("a@test.com");
        assertThat(user.toString()).contains("email=a@test.com", "firstName=A");
    }

    @Test
    void userAccessors() {
        User user = new User();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(3L);
        Wallet wallet = new Wallet();
        user.setPortfolio(portfolio);
        user.setWallet(wallet);
        user.setLastName("Last");
        user.setPassword("hash");

        assertThat(user.getPortfolio().getId()).isEqualTo(3L);
        assertThat(user.getWallet()).isSameAs(wallet);
        assertThat(user.getLastName()).isEqualTo("Last");
        assertThat(user.getPassword()).isEqualTo("hash");
    }

    @Test
    void roleAndPrivilegeEqualityIsBasedOnName() {
        Role role = new Role("ROLE_USER");
        role.setId(1L);
        Role sameName = new Role("ROLE_USER");
        sameName.setId(2L);
        role.setUsers(List.of(user("a@test.com")));

        assertThat(role).isEqualTo(sameName).hasSameHashCodeAs(sameName).isEqualTo(role);
        assertThat(role).isNotEqualTo(new Role("ROLE_ADMIN")).isNotEqualTo(null).isNotEqualTo("ROLE_USER");
        assertThat(role.getUsers()).hasSize(1);
        assertThat(role.toString()).contains("name=ROLE_USER", "id=1");

        Privilege privilege = new Privilege("READ_PRIVILEGE");
        privilege.setId(5L);
        privilege.setRoles(List.of(role));
        assertThat(privilege).isEqualTo(new Privilege("READ_PRIVILEGE")).hasSameHashCodeAs(new Privilege("READ_PRIVILEGE"));
        assertThat(privilege).isNotEqualTo(new Privilege("WRITE_PRIVILEGE")).isNotEqualTo(new Privilege()).isNotEqualTo(null);
        assertThat(new Privilege()).isEqualTo(new Privilege()).isNotEqualTo(privilege);
        assertThat(privilege.getRoles()).containsExactly(role);
        assertThat(privilege.getId()).isEqualTo(5L);
        assertThat(privilege.toString()).contains("name=READ_PRIVILEGE", "id=5");
    }

    @Test
    void verificationTokenExpiresInOneDay() {
        long before = System.currentTimeMillis();
        VerificationToken token = new VerificationToken("token", user("a@test.com"));

        long minutesUntilExpiry = (token.getExpiryDate().getTime() - before) / 60_000;
        assertThat(minutesUntilExpiry).isBetween(60L * 24 - 1, 60L * 24);
        assertThat(new VerificationToken("only-token").getUser()).isNull();
    }

    @Test
    void verificationTokenUpdateReplacesTokenAndExpiry() {
        VerificationToken token = new VerificationToken("old", user("a@test.com"));
        token.setExpiryDate(new Date(0));

        token.updateToken("new");

        assertThat(token.getToken()).isEqualTo("new");
        assertThat(token.getExpiryDate()).isAfter(new Date());
        assertThat(token.toString()).contains("String=new");
    }

    @Test
    void verificationTokenEquality() {
        Date expiry = new Date(1000);
        VerificationToken token = new VerificationToken("t", user("a@test.com"));
        token.setExpiryDate(expiry);
        VerificationToken same = new VerificationToken("t", user("a@test.com"));
        same.setExpiryDate(expiry);

        assertThat(token).isEqualTo(same).hasSameHashCodeAs(same).isEqualTo(token);
        assertThat(token).isNotEqualTo(null).isNotEqualTo("t");

        VerificationToken otherToken = new VerificationToken("other", user("a@test.com"));
        otherToken.setExpiryDate(expiry);
        VerificationToken otherUser = new VerificationToken("t", user("b@test.com"));
        otherUser.setExpiryDate(expiry);
        VerificationToken otherExpiry = new VerificationToken("t", user("a@test.com"));
        otherExpiry.setExpiryDate(new Date(2000));
        assertThat(token).isNotEqualTo(otherToken).isNotEqualTo(otherUser).isNotEqualTo(otherExpiry);

        VerificationToken empty = new VerificationToken();
        assertThat(empty).isEqualTo(new VerificationToken()).hasSameHashCodeAs(new VerificationToken());
        assertThat(empty).isNotEqualTo(token);
        assertThat(token).isNotEqualTo(empty);
    }

    @Test
    void passwordResetTokenBehavesLikeVerificationToken() {
        long before = System.currentTimeMillis();
        PasswordResetToken token = new PasswordResetToken("t", user("a@test.com"));
        assertThat((token.getExpiryDate().getTime() - before) / 60_000).isBetween(60L * 24 - 1, 60L * 24);
        assertThat(new PasswordResetToken("only-token").getUser()).isNull();

        Date expiry = new Date(1000);
        token.setExpiryDate(expiry);
        PasswordResetToken same = new PasswordResetToken("t", user("a@test.com"));
        same.setExpiryDate(expiry);
        assertThat(token).isEqualTo(same).hasSameHashCodeAs(same).isEqualTo(token);
        assertThat(token).isNotEqualTo(null).isNotEqualTo("t");

        PasswordResetToken otherToken = new PasswordResetToken("other", user("a@test.com"));
        otherToken.setExpiryDate(expiry);
        PasswordResetToken otherUser = new PasswordResetToken("t", user("b@test.com"));
        otherUser.setExpiryDate(expiry);
        PasswordResetToken otherExpiry = new PasswordResetToken("t", user("a@test.com"));
        otherExpiry.setExpiryDate(new Date(2000));
        assertThat(token).isNotEqualTo(otherToken).isNotEqualTo(otherUser).isNotEqualTo(otherExpiry);

        PasswordResetToken empty = new PasswordResetToken();
        assertThat(empty).isEqualTo(new PasswordResetToken()).hasSameHashCodeAs(new PasswordResetToken());
        assertThat(empty).isNotEqualTo(token);
        assertThat(token).isNotEqualTo(empty);

        token.setUser(user("c@test.com"));
        token.setToken("x");
        token.updateToken("y");
        assertThat(token.getToken()).isEqualTo("y");
        assertThat(token.getExpiryDate()).isAfter(new Date());
        assertThat(token.getUser().getEmail()).isEqualTo("c@test.com");
        assertThat(token.toString()).contains("y");
    }

    @Test
    void deviceMetadataValueSemantics() {
        Date loggedIn = new Date(1000);
        DeviceMetadata device = deviceMetadata(loggedIn);
        DeviceMetadata same = deviceMetadata(loggedIn);

        assertThat(device).isEqualTo(same).hasSameHashCodeAs(same).isEqualTo(device);
        assertThat(device).isNotEqualTo(null).isNotEqualTo("device");
        same.setLocation("Berlin");
        assertThat(device).isNotEqualTo(same);
        assertThat(device.getId()).isEqualTo(1L);
        assertThat(device.getLocation()).isEqualTo("Kyiv");
        assertThat(device.toString()).contains("userId=2", "deviceDetails='Chrome'", "location='Kyiv'");
    }

    private static DeviceMetadata deviceMetadata(Date loggedIn) {
        DeviceMetadata device = new DeviceMetadata();
        device.setId(1L);
        device.setUserId(2L);
        device.setDeviceDetails("Chrome");
        device.setLocation("Kyiv");
        device.setLastLoggedIn(loggedIn);
        return device;
    }

    @Test
    void walletAndCategoryAccessors() {
        Wallet wallet = new Wallet();
        wallet.setId(1L);
        Account account = new Account("Cash", null, wallet);
        Transaction transaction = new Transaction();
        Category category = new Category();
        category.setId(2L);
        category.setName("Food");
        category.setWallet(wallet);

        assertThat(wallet.setAccountsAndReturnAccounts(List.of(account))).containsExactly(account);
        wallet.setTransactions(List.of(transaction));
        wallet.setCategories(List.of(category));

        assertThat(wallet.getAccounts()).containsExactly(account);
        assertThat(wallet.getTransactions()).containsExactly(transaction);
        assertThat(wallet.getCategories()).containsExactly(category);
        assertThat(category.getId()).isEqualTo(2L);
        assertThat(category.getWallet()).isSameAs(wallet);
        assertThat(account.getWallet()).isSameAs(wallet);
    }
}
