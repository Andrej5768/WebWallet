package com.andrewcorp.webwallet.auth.twofactor;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    Optional<RecoveryCode> findFirstByUserIdAndCodeHashAndUsedAtIsNull(Long userId, String codeHash);

    long countByUserIdAndUsedAtIsNull(Long userId);

    /** Marks the code used unless it already is; 0 means another request used it first. */
    @Modifying(flushAutomatically = true)
    @Query("update RecoveryCode c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    int markUsedIfUnused(Long id, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("delete from RecoveryCode c where c.userId = :userId")
    void deleteByUserId(Long userId);
}
