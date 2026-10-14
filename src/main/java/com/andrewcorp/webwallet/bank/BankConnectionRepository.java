package com.andrewcorp.webwallet.bank;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BankConnectionRepository extends JpaRepository<BankConnection, Long> {

    Optional<BankConnection> findByUserIdAndProvider(Long userId, BankProvider provider);

    /** Locks the connection, so that a webhook and a synchronization of the same connection do not overlap. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BankConnection> findLockedByProviderAndWebhookSecret(BankProvider provider, String webhookSecret);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BankConnection c where c.id = :id")
    Optional<BankConnection> findLockedById(Long id);

    List<BankConnection> findByProvider(BankProvider provider);
}
