package com.andrewcorp.webwallet.auth.token;

import com.andrewcorp.webwallet.user.User;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByTokenHashAndType(String tokenHash, UserTokenType type);

    @Modifying(flushAutomatically = true)
    @Query("delete from UserToken t where t.user = :user and t.type = :type")
    void deleteByUserAndType(User user, UserTokenType type);

    @Modifying(flushAutomatically = true)
    @Query("delete from UserToken t where t.expiresAt < :before or t.usedAt < :before")
    int deleteStale(Instant before);
}
