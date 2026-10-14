package com.andrewcorp.webwallet.ledger.payee;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayeeRepository extends JpaRepository<Payee, Long> {

    List<Payee> findByUserIdOrderByNameAsc(Long userId);

    Optional<Payee> findByIdAndUserId(Long id, Long userId);

    /** Uses the unique index on {@code lower(name)}. */
    @Query("select p from Payee p where p.userId = :userId and lower(p.name) = lower(:name)")
    Optional<Payee> findByUserIdAndNameIgnoreCase(Long userId, String name);
}
