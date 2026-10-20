package com.andrewcorp.webwallet.auth.google;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** A started Google sign-in or account link, until Google redirects back. Only the hash of the state is stored. */
@Entity
@Table(name = "oauth_states")
public class OAuthState {

    public enum Purpose {
        /** Sign in or sign up. */
        LOGIN,
        /** Link a Google account to the signed-in user. */
        LINK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String stateHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    /** The signed-in user for {@link Purpose#LINK}. */
    private Long userId;

    /** PKCE: the code is exchanged only together with this verifier. */
    @Column(nullable = false)
    private String codeVerifier;

    /** Must come back in the ID token, so that a token issued for another sign-in is rejected. */
    @Column(nullable = false)
    private String nonce;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected OAuthState() {}

    public OAuthState(
            String stateHash, Purpose purpose, Long userId, String codeVerifier, String nonce, Instant expiresAt) {
        this.stateHash = stateHash;
        this.purpose = purpose;
        this.userId = userId;
        this.codeVerifier = codeVerifier;
        this.nonce = nonce;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Purpose getPurpose() {
        return purpose;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public String getNonce() {
        return nonce;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
