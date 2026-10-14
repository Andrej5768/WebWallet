package com.andrewcorp.webwallet.auth.token;

import com.andrewcorp.webwallet.user.User;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByTokenHashAndType(String tokenHash, UserTokenType type);

    /** Marks the token used if it is still usable; 0 means it is expired or another request used it first. */
    @Modifying(flushAutomatically = true)
    @Query("update UserToken t set t.usedAt = :now where t.id = :id and t.usedAt is null and t.expiresAt > :now")
    int markUsedIfUsable(Long id, Instant now);

    @Modifying(flushAutomatically = true)
    @Query("delete from UserToken t where t.user = :user and t.type = :type")
    void deleteByUserAndType(User user, UserTokenType type);

    @Modifying(flushAutomatically = true)
    @Query("delete from UserToken t where t.expiresAt < :before or t.usedAt < :before")
    int deleteStale(Instant before);
}
