package com.andrewcorp.webwallet.ledger.payee;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayeeRepository extends JpaRepository<Payee, Long> {

    List<Payee> findByUserIdOrderByNameAsc(Long userId);

    Optional<Payee> findByIdAndUserId(Long id, Long userId);

    Optional<Payee> findByUserIdAndNameIgnoreCase(Long userId, String name);
}
