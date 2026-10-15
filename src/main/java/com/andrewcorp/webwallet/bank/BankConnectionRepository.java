package com.andrewcorp.webwallet.bank;

import jakarta.persistence.LockModeType;
import java.time.Instant;
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

    /**
     * Ids of the connections that may make a request (the last one was at or before {@code lastRequestBefore}) and
     * have something to do: a synchronization is due, or the webhook has to be registered or removed.
     *
     * @param webhooks whether webhooks can be registered (the public URL of the backend is known)
     */
    @Query("""
            select c.id from BankConnection c
            where c.provider = :provider
              and (c.lastRequestAt is null or c.lastRequestAt <= :lastRequestBefore)
              and (c.nextSyncAt <= :now
                   or (c.syncMode = com.andrewcorp.webwallet.bank.SyncMode.WEBHOOK and c.webhookRegistered = false
                       and :webhooks = true)
                   or (c.webhookRegistered = true
                       and (c.syncMode <> com.andrewcorp.webwallet.bank.SyncMode.WEBHOOK or :webhooks = false)))
            order by c.nextSyncAt, c.id""")
    List<Long> findDue(BankProvider provider, Instant now, Instant lastRequestBefore, boolean webhooks);
}
