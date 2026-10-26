package com.andrewcorp.webwallet.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Expects an already normalized (lower case) email. */
    Optional<User> findByEmail(String email);

    Optional<User> findByGoogleSubject(String googleSubject);

    @Query("select u.id from User u where u.deletionScheduledAt < :now")
    List<Long> findIdsScheduledBefore(Instant now);

    @Query("select u.id from User u where u.demoExpiresAt < :now")
    List<Long> findDemoIdsExpiredBefore(Instant now);

    /** The token version of an enabled user, used to validate access tokens. */
    @Query("select u.tokenVersion from User u where u.id = :id and u.enabled = true")
    Optional<Integer> findActiveTokenVersion(Long id);
}
