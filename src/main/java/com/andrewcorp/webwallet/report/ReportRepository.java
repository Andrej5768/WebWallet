package com.andrewcorp.webwallet.report;

import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Read-only aggregation queries for the reports. */
public interface ReportRepository extends Repository<Transaction, Long> {

    /*
     * Amounts are summed in the database per day and currency: that is all the conversion with daily rates needs.
     */

    /** Category parts of expenses and incomes in the period, summed per day, currency and category. */
    @Query("""
            select t.date as date, a.currency as currency, t.type as type, c.id as categoryId, c.kind as kind,
                   sum(s.amount) as amount
            from TransactionSplit s join s.transaction t join t.account a left join s.category c
            where t.userId = :userId and t.type <> :transfer and t.adjustment = false
              and t.date between :from and :to
            group by t.date, a.currency, t.type, c.id, c.kind""")
    List<SplitRow> findSplits(Long userId, LocalDate from, LocalDate to, TransactionType transfer);

    /**
     * Spending with a payee in the period, summed per day and currency: expenses, minus refunds (incomes in an
     * expense category).
     */
    @Query("""
            select p.id as payeeId, p.name as payeeName, t.date as date, a.currency as currency,
                   sum(case when t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.EXPENSE
                            then s.amount else -s.amount end) as amount,
                   count(distinct t.id) as transactions
            from TransactionSplit s join s.transaction t join t.payee p join t.account a left join s.category c
            where t.userId = :userId and t.date between :from and :to and t.adjustment = false
              and (t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.EXPENSE
                   or (t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.INCOME
                       and c.kind = com.andrewcorp.webwallet.ledger.category.CategoryKind.EXPENSE))
            group by p.id, p.name, t.date, a.currency""")
    List<PayeeRow> findPayeeExpenses(Long userId, LocalDate from, LocalDate to);

    /** Balance changes of the source accounts up to the date, summed per account and month. */
    @Query("""
            select t.account.id as accountId, extract(year from t.date) as year, extract(month from t.date) as month,
                   sum(case when t.type = com.andrewcorp.webwallet.ledger.transaction.TransactionType.INCOME
                            then t.amount else -t.amount end) as change
            from Transaction t
            where t.userId = :userId and t.date <= :to
            group by t.account.id, extract(year from t.date), extract(month from t.date)""")
    List<MonthChange> findSourceChanges(Long userId, LocalDate to);

    /** Money received by the target accounts of transfers up to the date, summed per account and month. */
    @Query("""
            select t.toAccount.id as accountId, extract(year from t.date) as year, extract(month from t.date) as month,
                   sum(t.toAmount) as change
            from Transaction t
            where t.userId = :userId and t.toAccount is not null and t.date <= :to
            group by t.toAccount.id, extract(year from t.date), extract(month from t.date)""")
    List<MonthChange> findTargetChanges(Long userId, LocalDate to);

    /**
     * Transfers between a budget account and an account outside the budget in the period, summed per day and pair of
     * accounts, in the currency of the source account.
     */
    @Query("""
            select t.date as date, a.currency as currency, a.inBudget as fromBudget, a.id as accountId,
                   a.name as accountName, ta.id as toAccountId, ta.name as toAccountName, sum(t.amount) as amount
            from Transaction t join t.account a join t.toAccount ta
            where t.userId = :userId and t.type = :transfer and a.inBudget <> ta.inBudget
              and t.date between :from and :to
            group by t.date, a.currency, a.inBudget, a.id, a.name, ta.id, ta.name""")
    List<BoundaryTransferRow> findBudgetBoundaryTransfers(
            Long userId, LocalDate from, LocalDate to, TransactionType transfer);

    interface SplitRow {
        LocalDate getDate();

        String getCurrency();

        TransactionType getType();

        Long getCategoryId();

        CategoryKind getKind();

        BigDecimal getAmount();
    }

    interface PayeeRow {
        Long getPayeeId();

        String getPayeeName();

        LocalDate getDate();

        String getCurrency();

        BigDecimal getAmount();

        long getTransactions();
    }

    interface MonthChange {
        Long getAccountId();

        int getYear();

        int getMonth();

        BigDecimal getChange();
    }

    interface BoundaryTransferRow {
        LocalDate getDate();

        String getCurrency();

        boolean getFromBudget();

        Long getAccountId();

        String getAccountName();

        Long getToAccountId();

        String getToAccountName();

        BigDecimal getAmount();
    }
}
