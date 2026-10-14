package com.andrewcorp.webwallet.bank;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankAccountLinkRepository extends JpaRepository<BankAccountLink, Long> {

    List<BankAccountLink> findByConnectionOrderByIdAsc(BankConnection connection);

    Optional<BankAccountLink> findByConnectionAndExternalAccountId(BankConnection connection, String externalAccountId);
}
