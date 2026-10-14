package com.andrewcorp.webwallet.ledger.payee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayeeRepository extends JpaRepository<Payee, Long> {

    List<Payee> findByUserIdOrderByNameAsc(Long userId);

    Optional<Payee> findByIdAndUserId(Long id, Long userId);

    Optional<Payee> findByUserIdAndNameIgnoreCase(Long userId, String name);
}
