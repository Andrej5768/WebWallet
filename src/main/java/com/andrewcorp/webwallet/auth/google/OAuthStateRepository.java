package com.andrewcorp.webwallet.auth.google;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OAuthStateRepository extends JpaRepository<OAuthState, Long> {

    Optional<OAuthState> findByStateHash(String stateHash);

    /** Deletes the state; 0 means another request used it first. */
    @Modifying(flushAutomatically = true)
    @Query("delete from OAuthState s where s.id = :id")
    int deleteIfPresent(Long id);

    @Modifying(flushAutomatically = true)
    @Query("delete from OAuthState s where s.expiresAt < :before")
    int deleteExpired(Instant before);
}
