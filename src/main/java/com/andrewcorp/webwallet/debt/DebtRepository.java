package com.andrewcorp.webwallet.debt;

import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** The transfers with debt accounts, which are the debt operations. */
public interface DebtRepository extends Repository<Transaction, Long> {

    @Query("""
            select t.date as date, t.amount as amount, a.currency as currency, a.type as accountType,
                   t.toAmount as toAmount, ta.currency as toCurrency, ta.type as toAccountType,
                   p.id as payeeId, p.name as payeeName
            from Transaction t join t.account a join t.toAccount ta left join t.payee p
            where t.userId = :userId and t.type = :transfer and (a.type = :debt or ta.type = :debt)
            order by t.date, t.id""")
    List<DebtRow> findDebtTransfers(Long userId, TransactionType transfer, AccountType debt);

    interface DebtRow {
        LocalDate getDate();

        BigDecimal getAmount();

        String getCurrency();

        AccountType getAccountType();

        BigDecimal getToAmount();

        String getToCurrency();

        AccountType getToAccountType();

        Long getPayeeId();

        String getPayeeName();
    }
}
