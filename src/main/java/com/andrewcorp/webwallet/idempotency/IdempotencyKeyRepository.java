package com.andrewcorp.webwallet.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndRequestKey(Long userId, UUID requestKey);

    @Modifying(flushAutomatically = true)
    @Query("delete from IdempotencyKey k where k.createdAt < :before")
    int deleteOlderThan(Instant before);
}
