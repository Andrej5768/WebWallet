package com.andrewcorp.webwallet.auth.twofactor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** One-time code for signing in without the authenticator app. Only its hash is stored. */
@Entity
@Table(name = "recovery_codes")
public class RecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String codeHash;

    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected RecoveryCode() {}

    public RecoveryCode(Long userId, String codeHash) {
        this.userId = userId;
        this.codeHash = codeHash;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
