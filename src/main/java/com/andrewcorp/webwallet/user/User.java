package com.andrewcorp.webwallet.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Always stored in lower case. */
    @Column(nullable = false, unique = true)
    private String email;

    /** Null for an account created with Google until the user sets a password. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private String firstName;

    /** Optional. */
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean emailVerified;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Increased to invalidate the access tokens already issued, see {@link #invalidateTokens()}. */
    @Column(nullable = false)
    private int tokenVersion;

    /** Language for emails: {@code en} or {@code uk}. */
    @Column(nullable = false)
    private String locale = "en";

    /** ISO 4217 currency of reports and the envelope budget. */
    @Column(nullable = false)
    private String baseCurrency = "UAH";

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Encrypted TOTP secret; null while two-factor authentication is off. */
    private String totpSecret;

    /** Encrypted secret of a setup that no code has confirmed yet. */
    private String totpPendingSecret;

    /** The last accepted TOTP time step, so that a code cannot be used twice. */
    private Long totpLastStep;

    private Instant totpEnabledAt;

    /** The Google account id ("sub" of the ID token) of a linked Google account. */
    private String googleSubject;

    /** Email of the linked Google account, shown in the settings. */
    private String googleEmail;

    protected User() {}

    public User(String email, String passwordHash, String firstName, String lastName, String locale) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.locale = locale;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    /** Makes the access tokens issued so far invalid, e.g. after a password change. */
    public void invalidateTokens() {
        tokenVersion++;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public String getGoogleSubject() {
        return googleSubject;
    }

    public String getGoogleEmail() {
        return googleEmail;
    }

    /** Links a Google account, or unlinks it with nulls. */
    public void setGoogleAccount(String subject, String email) {
        this.googleSubject = subject;
        this.googleEmail = email;
    }

    public boolean isTwoFactorEnabled() {
        return totpSecret != null;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public String getTotpPendingSecret() {
        return totpPendingSecret;
    }

    public void setTotpPendingSecret(String totpPendingSecret) {
        this.totpPendingSecret = totpPendingSecret;
    }

    public Long getTotpLastStep() {
        return totpLastStep;
    }

    public void setTotpLastStep(Long totpLastStep) {
        this.totpLastStep = totpLastStep;
    }

    public Instant getTotpEnabledAt() {
        return totpEnabledAt;
    }

    /** Turns two-factor authentication on with the confirmed secret, or off with null. */
    public void setTotpSecret(String totpSecret, Instant now) {
        this.totpSecret = totpSecret;
        this.totpPendingSecret = null;
        this.totpEnabledAt = totpSecret == null ? null : now;
    }
}
