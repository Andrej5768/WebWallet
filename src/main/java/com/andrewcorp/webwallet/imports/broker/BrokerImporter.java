package com.andrewcorp.webwallet.imports.broker;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.BrokerOperation;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.BrokerTicker;
import com.andrewcorp.webwallet.investment.Instrument;
import com.andrewcorp.webwallet.investment.InvestmentOperation;
import com.andrewcorp.webwallet.investment.InvestmentOperationRepository;
import com.andrewcorp.webwallet.investment.LotLedger;
import com.andrewcorp.webwallet.investment.OperationType;
import com.andrewcorp.webwallet.ledger.LedgerLookups;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Writes the operations of broker reports to a brokerage account: the given one, else the account named after the
 * broker, else a new one. Operations imported before (by their key) are skipped, so the same reports may be imported
 * again, also overlapping ones. Runs in the caller's transaction, which a preview rolls back.
 */
@Component
public class BrokerImporter {

    private final AccountRepository accounts;
    private final LedgerLookups lookups;
    private final InvestmentOperationRepository operations;

    public BrokerImporter(AccountRepository accounts, LedgerLookups lookups, InvestmentOperationRepository operations) {
        this.accounts = accounts;
        this.lookups = lookups;
        this.operations = operations;
    }

    /**
     * @param account   the brokerage account
     * @param imported  the keys of the operations written
     * @param duplicate the keys of the operations imported before
     * @param holdings  the instrument of each ticker of the written operations
     * @param shortfalls sales of more than is held on their dates, once all operations are written
     */
    public record Result(
            Account account,
            boolean accountCreated,
            Set<String> imported,
            Set<String> duplicate,
            Map<String, Instrument> holdings,
            List<Shortfall> shortfalls) {}

    /** A sale of a ticker on a date of more than was held then. */
    public record Shortfall(String ticker, LocalDate date) {}

    /**
     * @param accountId  null for the account of the broker, created when there is none
     * @param excluded   keys of operations the user left out
     * @param instrument the instrument of a ticker, asked once per ticker that is written
     */
    public Result run(
            Long userId,
            Long accountId,
            BrokerStatement statement,
            Set<String> excluded,
            Function<BrokerTicker, Instrument> instrument) {
        boolean created = false;
        Account account = accountId != null ? find(userId, accountId) : named(userId, statement.broker());
        if (account == null) {
            account = accounts.save(
                    new Account(userId, statement.broker(), AccountType.INVESTMENT, statement.currency()));
            created = true;
        } else {
            // Two imports at once would both miss the other's operations
            accounts.lock(account.getId());
        }
        Set<String> existing = created ? Set.of() : operations.findExternalIds(account.getId());
        Map<String, BrokerTicker> tickers = new LinkedHashMap<>();
        statement.tickers().forEach(ticker -> tickers.put(ticker.ticker(), ticker));

        Set<String> imported = new HashSet<>();
        Set<String> duplicate = new HashSet<>();
        Map<String, Instrument> holdings = new LinkedHashMap<>();
        // In the order of the reports, so that the ids keep the order of the trades of a day
        for (BrokerOperation draft : statement.operations()) {
            if (draft.skip() != null || excluded.contains(draft.key())) {
                continue;
            }
            if (existing.contains(draft.key())) {
                duplicate.add(draft.key());
                continue;
            }
            Instrument held = null;
            if (draft.ticker() != null) {
                BrokerTicker ticker = tickers.get(draft.ticker());
                held = holdings.computeIfAbsent(draft.ticker(), key -> instrument.apply(ticker));
                boolean trade = draft.type() == OperationType.BUY || draft.type() == OperationType.SELL;
                if (trade && !held.getCurrency().equals(draft.currency())) {
                    throw ApiException.invalid(
                            "instruments", "error.import.instrumentCurrency", draft.ticker(), draft.currency());
                }
            }
            operations.save(operation(userId, account, held, draft));
            imported.add(draft.key());
        }
        operations.flush();

        List<Shortfall> shortfalls = new ArrayList<>();
        for (Map.Entry<String, Instrument> held : holdings.entrySet()) {
            try {
                LotLedger.of(operations.findByAccountAndInstrument(account, held.getValue()));
            } catch (LotLedger.Shortfall e) {
                shortfalls.add(new Shortfall(held.getKey(), e.date()));
            }
        }
        return new Result(account, created, imported, duplicate, holdings, shortfalls);
    }

    private Account find(Long userId, Long accountId) {
        return lookups.investmentAccount(userId, accountId, "accountId");
    }

    /** The brokerage account named after the broker that is not archived; null when there is none. */
    private Account named(Long userId, String broker) {
        return accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .filter(account -> account.getType() == AccountType.INVESTMENT
                        && !account.isArchived()
                        && account.getName().equalsIgnoreCase(broker))
                .findFirst()
                .orElse(null);
    }

    private static InvestmentOperation operation(
            Long userId, Account account, Instrument instrument, BrokerOperation draft) {
        InvestmentOperation operation = new InvestmentOperation(userId);
        operation.setAccount(account);
        operation.setInstrument(instrument);
        operation.setType(draft.type());
        operation.setDate(draft.time().toLocalDate());
        operation.setQuantity(draft.quantity());
        operation.setPrice(draft.price());
        operation.setAmount(draft.amount());
        operation.setFee(draft.fee());
        operation.setTax(draft.tax());
        operation.setCurrency(draft.currency());
        operation.setToAmount(draft.toAmount());
        operation.setToCurrency(draft.toCurrency());
        operation.setExternalId(draft.key());
        // What the report says explains a fee or a movement of money; a trade or dividend speaks for itself
        boolean money = draft.type() == OperationType.FEE
                || draft.type() == OperationType.DEPOSIT
                || draft.type() == OperationType.WITHDRAWAL;
        operation.setComment(money ? draft.description() : null);
        return operation;
    }
}
