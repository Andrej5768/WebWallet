package com.andrewcorp.webwallet.ledger.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    /** Net change of each account caused by the transactions where it is the source account. */
    @Query("""
            select t.account.id as accountId,
                   sum(case when t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.INCOME
                            then t.amount else -t.amount end) as change
            from Transaction t where t.userId = :userId group by t.account.id""")
    List<AccountChange> sourceChanges(Long userId);

    /** Money received by each account as the target of transfers. */
    @Query("""
            select t.toAccount.id as accountId, sum(t.toAmount) as change
            from Transaction t where t.userId = :userId and t.toAccount is not null group by t.toAccount.id""")
    List<AccountChange> transferIncome(Long userId);

    @Query("select count(t) > 0 from Transaction t where t.account.id = :accountId or t.toAccount.id = :accountId")
    boolean existsForAccount(Long accountId);

    @Query("select count(s) > 0 from TransactionSplit s where s.category.id = :categoryId")
    boolean existsForCategory(Long categoryId);

    @Query("select count(t) > 0 from Transaction t where t.payee.id = :payeeId")
    boolean existsForPayee(Long payeeId);

    interface AccountChange {

        Long getAccountId();

        BigDecimal getChange();
    }
}
