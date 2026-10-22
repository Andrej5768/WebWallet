package com.andrewcorp.webwallet.auth.token;

import com.andrewcorp.webwallet.common.web.ClientInfo;
import com.andrewcorp.webwallet.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token, rotated on every use. All tokens issued from one login share a family, so reuse of an already
 * rotated token can revoke the whole session.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private UUID familyId;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** Which client keeps the token: null for the web client (a cookie), the apps otherwise (the body). */
    @Column(length = 16)
    @Enumerated(EnumType.STRING)
    private ClientInfo.Kind client;

    protected RefreshToken() {}

    public RefreshToken(User user, UUID familyId, String tokenHash, Instant expiresAt, ClientInfo.Kind client) {
        this.user = user;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.client = client == ClientInfo.Kind.WEB ? null : client;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    /** The client that keeps the token; the web client for tokens issued before clients were told apart. */
    public ClientInfo.Kind getClient() {
        return client == null ? ClientInfo.Kind.WEB : client;
    }
}
