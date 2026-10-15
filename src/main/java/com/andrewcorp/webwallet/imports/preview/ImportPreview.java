package com.andrewcorp.webwallet.imports.preview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A parsed import waiting for the user to review it, kept for a limited time. Only the parsed records are stored,
 * never the uploaded file or a token.
 */
@Entity
@Table(name = "import_previews")
public class ImportPreview {

    /** Random, so that it cannot be guessed. */
    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ImportSource source;

    /** The parsed input as JSON, imported on confirmation. */
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String input;

    /** The operations and the summary shown to the user, as JSON. */
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String preview;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    protected ImportPreview() {}

    public ImportPreview(
            Long userId, ImportSource source, String input, String preview, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.source = source;
        this.input = input;
        this.preview = preview;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public ImportSource getSource() {
        return source;
    }

    public String getInput() {
        return input;
    }

    public String getPreview() {
        return preview;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
