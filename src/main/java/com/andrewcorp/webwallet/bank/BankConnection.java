package com.andrewcorp.webwallet.bank;

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

/**
 * Access of a user to a bank API. The token is stored encrypted.
 */
@Entity
@Table(name = "bank_connections")
public class BankConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private BankProvider provider;

    @Column(nullable = false)
    private String encryptedToken;

    private String clientName;

    @Column(nullable = false)
    private String webhookSecret;

    private Instant lastRequestAt;

    private String lastError;

    @Column(nullable = false)
    private boolean webhookRegistered;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected BankConnection() {}

    public BankConnection(Long userId, BankProvider provider, String encryptedToken, String webhookSecret) {
        this.userId = userId;
        this.provider = provider;
        this.encryptedToken = encryptedToken;
        this.webhookSecret = webhookSecret;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public BankProvider getProvider() {
        return provider;
    }

    public String getEncryptedToken() {
        return encryptedToken;
    }

    public void setEncryptedToken(String encryptedToken) {
        this.encryptedToken = encryptedToken;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public Instant getLastRequestAt() {
        return lastRequestAt;
    }

    public void setLastRequestAt(Instant lastRequestAt) {
        this.lastRequestAt = lastRequestAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public boolean isWebhookRegistered() {
        return webhookRegistered;
    }

    public void setWebhookRegistered(boolean webhookRegistered) {
        this.webhookRegistered = webhookRegistered;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
