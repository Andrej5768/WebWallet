package com.andrewcorp.webwallet.telegram;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** The Telegram chat of a user. */
@Entity
@Table(name = "telegram_links")
public class TelegramLink {

    @Id
    private Long userId;

    @Column(nullable = false, unique = true)
    private long chatId;

    private String username;

    private String firstName;

    /** Account of the operations written to the bot without naming one; the first card account when null. */
    private Long quickEntryAccountId;

    @Column(nullable = false)
    private Instant linkedAt;

    protected TelegramLink() {}

    public TelegramLink(Long userId, long chatId) {
        this.userId = userId;
        this.chatId = chatId;
    }

    @PrePersist
    void onCreate() {
        if (linkedAt == null) {
            linkedAt = Instant.now();
        }
    }

    public Long getUserId() {
        return userId;
    }

    public long getChatId() {
        return chatId;
    }

    public void setChatId(long chatId) {
        this.chatId = chatId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public Long getQuickEntryAccountId() {
        return quickEntryAccountId;
    }

    public void setQuickEntryAccountId(Long quickEntryAccountId) {
        this.quickEntryAccountId = quickEntryAccountId;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public void setLinkedAt(Instant linkedAt) {
        this.linkedAt = linkedAt;
    }
}
