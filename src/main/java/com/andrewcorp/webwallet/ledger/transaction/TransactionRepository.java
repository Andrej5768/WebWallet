package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    @Query(
            "select t.externalId from Transaction t where t.userId = :userId and t.source = :source and t.externalId is not null")
    Set<String> findExternalIds(Long userId, TransactionSource source);

    Optional<Transaction> findByUserIdAndSourceAndExternalId(Long userId, TransactionSource source, String externalId);

    /** Transactions that stand for the bank operation, on either side of a transfer. */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and (t.bankId = :bankId or t.toBankId = :bankId) order by t.id""")
    List<Transaction> findByBankId(Long userId, String bankId);

    /** Expenses or incomes of the account with this amount around the date, not yet matched to a bank operation. */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.account = :account and t.type = :type and t.amount = :amount
              and t.date between :from and :to and t.bankId is null""")
    List<Transaction> findMatchCandidates(
            Long userId, Account account, TransactionType type, BigDecimal amount, LocalDate from, LocalDate to);

    /** Transfers from the account with this amount around the date whose outgoing half is not matched yet. */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.TRANSFER
              and t.account = :account and t.amount = :amount and t.date between :from and :to and t.bankId is null""")
    List<Transaction> findOutgoingTransferMatches(
            Long userId, Account account, BigDecimal amount, LocalDate from, LocalDate to);

    /** Transfers to the account with this amount around the date whose incoming half is not matched yet. */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.TRANSFER
              and t.toAccount = :account and t.toAmount = :amount and t.date between :from and :to
              and t.toBankId is null""")
    List<Transaction> findIncomingTransferMatches(
            Long userId, Account account, BigDecimal amount, LocalDate from, LocalDate to);

    /**
     * Imported expenses or incomes on other accounts of the same currency that look like one half of a transfer
     * between own accounts (a transfer MCC or bank category, or no category), possibly with a fee (the amount in the
     * given range). Transactions entered manually are not merged: they are what the user meant.
     */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.type = :type and t.account <> :account and t.account.currency = :currency
              and t.date between :from and :to and t.amount between :minAmount and :maxAmount
              and t.source <> com.andrewcorp.webwallet.ledger.transaction.TransactionSource.MANUAL
              and (t.mcc = :transferMcc or t.bankCategory in :transferCategories
                   or not exists (select s from TransactionSplit s where s.transaction = t and s.category is not null))""")
    List<Transaction> findTransferCandidates(
            Long userId,
            TransactionType type,
            Account account,
            String currency,
            LocalDate from,
            LocalDate to,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Short transferMcc,
            Collection<String> transferCategories);

    /** Expenses and incomes without a category and without a split into several categories. */
    @Query("""
            select t from Transaction t join t.splits s
            where t.userId = :userId and t.type <> com.andrewcorp.webwallet.ledger.transaction.TransactionType.TRANSFER
              and s.category is null and size(t.splits) = 1""")
    List<Transaction> findUncategorized(Long userId);

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

    boolean existsByAccountIdOrToAccountId(Long accountId, Long toAccountId);

    /** Ids of all bank operations of the user, to recognize many operations of an import at once. */
    @Query("select t.bankId from Transaction t where t.userId = :userId and t.bankId is not null")
    Set<String> findBankIds(Long userId);

    @Query("select t.toBankId from Transaction t where t.userId = :userId and t.toBankId is not null")
    Set<String> findToBankIds(Long userId);

    interface AccountChange {

        Long getAccountId();

        BigDecimal getChange();
    }
}
