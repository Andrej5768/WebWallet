package com.andrewcorp.webwallet.ledger.transaction;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferDismissalRepository extends JpaRepository<TransferDismissal, Long> {

    List<TransferDismissal> findByUserId(Long userId);

    boolean existsByExpenseIdAndIncomeId(Long expenseId, Long incomeId);
}
