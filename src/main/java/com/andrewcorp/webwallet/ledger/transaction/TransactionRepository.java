package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    @Query("select t.externalId from Transaction t where t.userId = :userId and t.source = :source and t.externalId is not null")
    Set<String> findExternalIds(Long userId, TransactionSource source);

    Optional<Transaction> findByUserIdAndSourceAndExternalId(Long userId, TransactionSource source, String externalId);

    /** Zen Money keeps bank ids with a prefix, e.g. {@code [monobank][reg]-<id>}. */
    Optional<Transaction> findFirstByUserIdAndBankReferenceEndingWith(Long userId, String bankId);

    /** Expenses or incomes of the account with this amount around the date, not yet matched to a bank operation. */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.account = :account and t.type = :type and t.amount = :amount
              and t.date between :from and :to and t.bankReference is null and t.source <> :excludedSource""")
    List<Transaction> findMatchCandidates(Long userId, Account account, TransactionType type, BigDecimal amount,
                                          LocalDate from, LocalDate to, TransactionSource excludedSource);

    /** Imported bank operations on other accounts that may be the other half of a transfer between own cards. */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.source = :source and t.type = :type and t.amount = :amount
              and t.date = :date and t.mcc = :mcc and t.account in :accounts""")
    List<Transaction> findTransferCounterparts(Long userId, TransactionSource source, TransactionType type,
                                               BigDecimal amount, LocalDate date, Short mcc, Collection<Account> accounts);

    /** Category of the latest categorized transaction with this payee, to categorize new ones the same way. */
    @Query("""
            select s.category from TransactionSplit s join s.transaction t
            where t.userId = :userId and t.payee = :payee and s.category is not null and s.category.kind = :kind
            order by t.date desc, t.id desc""")
    List<Category> findCategoriesUsedForPayee(Long userId, Payee payee, CategoryKind kind, Pageable pageable);

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
