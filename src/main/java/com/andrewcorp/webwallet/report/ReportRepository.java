package com.andrewcorp.webwallet.report;

import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Read-only aggregation queries for the reports. */
public interface ReportRepository extends Repository<Transaction, Long> {

    /** Category parts of expenses and incomes in the period. */
    @Query("""
            select t.date as date, a.currency as currency, t.type as type, c.id as categoryId, s.amount as amount
            from TransactionSplit s join s.transaction t join t.account a left join s.category c
            where t.userId = :userId and t.type <> :transfer and t.date between :from and :to""")
    List<SplitRow> findSplits(Long userId, LocalDate from, LocalDate to, TransactionType transfer);

    /** Expenses with a payee in the period. */
    @Query("""
            select p.id as payeeId, p.name as payeeName, t.date as date, a.currency as currency, t.amount as amount
            from Transaction t join t.payee p join t.account a
            where t.userId = :userId and t.type = :expense and t.date between :from and :to""")
    List<PayeeRow> findPayeeExpenses(Long userId, LocalDate from, LocalDate to, TransactionType expense);

    /** Every balance change up to the date: the source side and, for transfers, the target side. */
    @Query("""
            select t.date as date, t.type as type, t.account.id as accountId, t.amount as amount,
                   ta.id as toAccountId, t.toAmount as toAmount
            from Transaction t left join t.toAccount ta
            where t.userId = :userId and t.date <= :to""")
    List<MovementRow> findMovements(Long userId, LocalDate to);

    interface SplitRow {
        LocalDate getDate();

        String getCurrency();

        TransactionType getType();

        Long getCategoryId();

        BigDecimal getAmount();
    }

    interface PayeeRow {
        Long getPayeeId();

        String getPayeeName();

        LocalDate getDate();

        String getCurrency();

        BigDecimal getAmount();
    }

    interface MovementRow {
        LocalDate getDate();

        TransactionType getType();

        Long getAccountId();

        BigDecimal getAmount();

        Long getToAccountId();

        BigDecimal getToAmount();
    }
}
