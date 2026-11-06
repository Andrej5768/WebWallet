package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.bank.SyncMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * An access request to Monobank waiting for the user to approve it in the bank app, with the settings of the
 * connection to create then. The request id the bank issued stands for the user in every later request, so it is
 * stored encrypted, like a token.
 */
@Entity
@Table(name = "monobank_auth_requests")
public class MonobankAuthRequest {

    public enum Status {
        PENDING,
        APPROVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private String encryptedRequestId;

    @Column(nullable = false, updatable = false)
    private String callbackSecret;

    @Column(nullable = false, updatable = false)
    private String acceptUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private LocalDate since;

    @Enumerated(EnumType.STRING)
    private SyncMode syncMode;

    private LocalTime windowStart;
    private LocalTime windowEnd;
    private LocalTime dailyAt;
    private Instant checkedAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected MonobankAuthRequest() {}

    public MonobankAuthRequest(
            Long userId,
            String encryptedRequestId,
            String callbackSecret,
            String acceptUrl,
            Instant createdAt,
            Instant expiresAt) {
        this.userId = userId;
        this.encryptedRequestId = encryptedRequestId;
        this.callbackSecret = callbackSecret;
        this.acceptUrl = acceptUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void setSettings(
            LocalDate since, SyncMode syncMode, LocalTime windowStart, LocalTime windowEnd, LocalTime dailyAt) {
        this.since = since;
        this.syncMode = syncMode;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.dailyAt = dailyAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEncryptedRequestId() {
        return encryptedRequestId;
    }

    public String getAcceptUrl() {
        return acceptUrl;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public LocalDate getSince() {
        return since;
    }

    public SyncMode getSyncMode() {
        return syncMode;
    }

    public LocalTime getWindowStart() {
        return windowStart;
    }

    public LocalTime getWindowEnd() {
        return windowEnd;
    }

    public LocalTime getDailyAt() {
        return dailyAt;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(Instant checkedAt) {
        this.checkedAt = checkedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
