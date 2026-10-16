package com.andrewcorp.webwallet.ledger.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    Optional<Account> findByIdAndUserId(Long id, Long userId);

    Optional<Account> findByUserIdAndExternalSourceAndExternalId(Long userId, String externalSource, String externalId);

    /** Locks the account until the end of the transaction, e.g. to change its operations one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lock(Long id);
}
