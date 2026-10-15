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
import java.time.LocalTime;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncMode syncMode = SyncMode.INTERVAL;

    /** Window of the {@link SyncMode#INTERVAL} mode, in Kyiv time. */
    @Column(nullable = false)
    private LocalTime windowStart = LocalTime.of(8, 0);

    @Column(nullable = false)
    private LocalTime windowEnd = LocalTime.of(23, 0);

    /** Time of the daily synchronization in the {@link SyncMode#DAILY} and {@link SyncMode#WEBHOOK} modes. */
    @Column(nullable = false)
    private LocalTime dailyAt = LocalTime.of(6, 0);

    /** Due moment of the next synchronization; every linked account is then requested up to at least this moment. */
    @Column(nullable = false)
    private Instant nextSyncAt;

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
        if (nextSyncAt == null) {
            nextSyncAt = createdAt;
        }
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

    public SyncMode getSyncMode() {
        return syncMode;
    }

    public void setSyncMode(SyncMode syncMode) {
        this.syncMode = syncMode;
    }

    public LocalTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(LocalTime windowStart) {
        this.windowStart = windowStart;
    }

    public LocalTime getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(LocalTime windowEnd) {
        this.windowEnd = windowEnd;
    }

    public LocalTime getDailyAt() {
        return dailyAt;
    }

    public void setDailyAt(LocalTime dailyAt) {
        this.dailyAt = dailyAt;
    }

    public Instant getNextSyncAt() {
        return nextSyncAt;
    }

    public void setNextSyncAt(Instant nextSyncAt) {
        this.nextSyncAt = nextSyncAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
