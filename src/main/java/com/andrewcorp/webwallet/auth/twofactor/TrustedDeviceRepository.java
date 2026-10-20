package com.andrewcorp.webwallet.auth.twofactor;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {

    Optional<TrustedDevice> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true)
    @Query("delete from TrustedDevice d where d.userId = :userId")
    void deleteByUserId(Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from TrustedDevice d where d.expiresAt < :before")
    int deleteExpired(Instant before);
}
