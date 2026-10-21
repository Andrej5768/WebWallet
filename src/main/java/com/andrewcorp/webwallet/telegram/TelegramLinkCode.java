package com.andrewcorp.webwallet.telegram;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The one-time code of the latest t.me link a user opened to connect a chat; a new link replaces it. */
@Entity
@Table(name = "telegram_link_codes")
public class TelegramLinkCode {

    @Id
    private Long userId;

    @Column(nullable = false, unique = true)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    protected TelegramLinkCode() {}

    public TelegramLinkCode(Long userId, String codeHash, Instant expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
