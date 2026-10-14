package com.andrewcorp.webwallet.ledger.rule;

import com.andrewcorp.webwallet.ledger.payee.Payee;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategorizationRuleRepository extends JpaRepository<CategorizationRule, Long> {

    List<CategorizationRule> findByUserIdOrderByIdAsc(Long userId);

    Optional<CategorizationRule> findByIdAndUserId(Long id, Long userId);

    /** The learned rule of a payee: only the payee condition. */
    @Query("""
            select r from CategorizationRule r
            where r.userId = :userId and r.learned = true and r.payee = :payee
              and r.payeePattern is null and r.mcc is null and r.bankCategory is null""")
    Optional<CategorizationRule> findLearnedForPayee(Long userId, Payee payee);
}
