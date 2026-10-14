package com.andrewcorp.webwallet.ledger.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    Optional<Account> findByIdAndUserId(Long id, Long userId);

    Optional<Account> findByUserIdAndExternalSourceAndExternalId(Long userId, String externalSource, String externalId);
}
