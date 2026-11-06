package com.andrewcorp.webwallet.bank.monobank;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MonobankAuthRequestRepository extends JpaRepository<MonobankAuthRequest, Long> {

    Optional<MonobankAuthRequest> findByIdAndUserId(Long id, Long userId);

    Optional<MonobankAuthRequest> findByCallbackSecret(String callbackSecret);

    /** Locks the request, so that the callback of the bank and the polling app do not connect twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MonobankAuthRequest r where r.id = :id")
    Optional<MonobankAuthRequest> findLockedById(Long id);

    @Modifying
    @Query("delete from MonobankAuthRequest r where r.expiresAt < :before")
    int deleteExpired(Instant before);
}
