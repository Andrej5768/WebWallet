package com.andrewcorp.webwallet.planning;

import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Queries on the transactions for recurring payments and the forecast. */
public interface PlanningRepository extends Repository<Transaction, Long> {

    /** Expenses or incomes of the account with an amount in the range around the date. */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.account = :account and t.type = :type
              and t.amount between :minAmount and :maxAmount and t.date between :from and :to""")
    List<Transaction> findCandidates(
            Long userId,
            Account account,
            TransactionType type,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            LocalDate from,
            LocalDate to);

    /** Transfers between the two accounts with an amount in the range around the date. */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.TRANSFER
              and t.account = :account and t.toAccount = :toAccount
              and t.amount between :minAmount and :maxAmount and t.date between :from and :to""")
    List<Transaction> findTransferCandidates(
            Long userId,
            Account account,
            Account toAccount,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            LocalDate from,
            LocalDate to);

    /** Transactions of the type on any account with an amount in the range around the date. */
    @Query("""
            select t from Transaction t
            where t.userId = :userId and t.type = :type
              and t.amount between :minAmount and :maxAmount and t.date between :from and :to""")
    List<Transaction> findAnyCandidates(
            Long userId,
            TransactionType type,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            LocalDate from,
            LocalDate to);

    /** Changes of the source accounts from the date on, summed per day and account. */
    @Query("""
            select t.date as date, t.account.id as accountId,
                   sum(case when t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.INCOME
                            then t.amount else -t.amount end) as change
            from Transaction t
            where t.userId = :userId and t.date >= :from
            group by t.date, t.account.id""")
    List<DayChange> findSourceChanges(Long userId, LocalDate from);

    /** Money received by the target accounts of transfers from the date on, summed per day and account. */
    @Query("""
            select t.date as date, t.toAccount.id as accountId, sum(t.toAmount) as change
            from Transaction t
            where t.userId = :userId and t.toAccount is not null and t.date >= :from
            group by t.date, t.toAccount.id""")
    List<DayChange> findTargetChanges(Long userId, LocalDate from);

    /** Expenses of the user in the period with their account, to estimate the everyday spending. */
    @Query("""
            select t.id as id, t.date as date, t.account.id as accountId, t.amount as amount
            from Transaction t
            where t.userId = :userId and t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.EXPENSE
              and t.date between :from and :to""")
    List<Expense> findExpenses(Long userId, LocalDate from, LocalDate to);

    /** Date of the oldest transaction of the user. */
    @Query("select min(t.date) from Transaction t where t.userId = :userId")
    LocalDate findFirstDate(Long userId);

    /** Expense parts of the transactions that paid recurring payments in the period, per day, currency and category. */
    @Query("""
            select t.date as date, a.currency as currency, s.category.id as categoryId, sum(s.amount) as amount
            from RecurringOccurrence o join o.transaction t join t.splits s join t.account a
            where o.recurring.userId = :userId
              and t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.EXPENSE
              and s.category is not null and t.date between :from and :to
            group by t.date, a.currency, s.category.id""")
    List<CategoryAmount> findRecurringExpenses(Long userId, LocalDate from, LocalDate to);

    interface CategoryAmount {
        LocalDate getDate();

        String getCurrency();

        Long getCategoryId();

        BigDecimal getAmount();
    }

    interface DayChange {
        LocalDate getDate();

        Long getAccountId();

        BigDecimal getChange();
    }

    interface Expense {
        Long getId();

        LocalDate getDate();

        Long getAccountId();

        BigDecimal getAmount();
    }
}
