package com.andrewcorp.webwallet.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The response of a create request sent with an {@code Idempotency-Key}: the same request of the same user sent
 * again gets this response back instead of creating a second resource. The hash of the request tells a retry from a
 * reuse of the key for something else.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private UUID requestKey;

    @Column(nullable = false)
    private String requestHash;

    @Column(nullable = false)
    private int status;

    private String contentType;

    @Column(nullable = false)
    private String responseBody;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyKey() {}

    public IdempotencyKey(
            Long userId,
            UUID requestKey,
            String requestHash,
            int status,
            String contentType,
            String responseBody,
            Instant createdAt) {
        this.userId = userId;
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        this.status = status;
        this.contentType = contentType;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getStatus() {
        return status;
    }

    public String getContentType() {
        return contentType;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
