package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Transactions of the ledger that move money of brokerage accounts. */
public interface HistoryRepository extends Repository<Transaction, Long> {

    /** Transactions from or to the accounts, oldest first. */
    @Query("""
            select t.date as date, t.type as type, t.account.id as accountId, t.amount as amount,
                   t.toAccount.id as toAccountId, t.toAmount as toAmount
            from Transaction t left join t.toAccount
            where t.account.id in :accountIds or t.toAccount.id in :accountIds
            order by t.date, t.id""")
    List<Movement> findMovements(Collection<Long> accountIds);

    /** The date of the first transaction from or to a brokerage account of anyone. */
    @Query("""
            select min(t.date) from Transaction t left join t.toAccount ta
            where t.account.type = com.andrewcorp.webwallet.ledger.account.AccountType.INVESTMENT
               or ta.type = com.andrewcorp.webwallet.ledger.account.AccountType.INVESTMENT""")
    LocalDate findFirstBrokerageDate();

    interface Movement {
        LocalDate getDate();

        TransactionType getType();

        Long getAccountId();

        BigDecimal getAmount();

        Long getToAccountId();

        BigDecimal getToAmount();
    }
}
