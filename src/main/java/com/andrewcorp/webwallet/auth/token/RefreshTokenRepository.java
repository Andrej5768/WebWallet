package com.andrewcorp.webwallet.auth.token;

import com.andrewcorp.webwallet.user.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revokes the token unless it is already revoked; 0 means another request used it first. */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
    int revokeIfActive(Long id, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    void revokeFamily(UUID familyId, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.user = :user and t.revokedAt is null")
    void revokeAllForUser(User user, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpired(Instant before);
}
