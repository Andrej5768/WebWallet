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
            where t.userId = :userId and t.type <> :transfer and t.date between :from and :to
            group by t.date, a.currency, t.type, c.id, c.kind""")
    List<SplitRow> findSplits(Long userId, LocalDate from, LocalDate to, TransactionType transfer);

    /** Expenses with a payee in the period, summed per day and currency. */
    @Query("""
            select p.id as payeeId, p.name as payeeName, t.date as date, a.currency as currency,
                   sum(t.amount) as amount, count(t) as transactions
            from Transaction t join t.payee p join t.account a
            where t.userId = :userId and t.type = :expense and t.date between :from and :to
            group by p.id, p.name, t.date, a.currency""")
    List<PayeeRow> findPayeeExpenses(Long userId, LocalDate from, LocalDate to, TransactionType expense);

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
}
