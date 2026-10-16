package com.andrewcorp.webwallet.ledger.account;

import com.andrewcorp.webwallet.budget.BudgetDtos.MonthResponse;
import com.andrewcorp.webwallet.budget.BudgetService;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.currency.ExchangeRateService;
import com.andrewcorp.webwallet.investment.InvestmentOperationRepository;
import com.andrewcorp.webwallet.investment.InvestmentValuation;
import com.andrewcorp.webwallet.ledger.account.dto.AccountRequest;
import com.andrewcorp.webwallet.ledger.account.dto.AccountResponse;
import com.andrewcorp.webwallet.ledger.account.dto.AccountSummaryResponse;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository.AccountChange;
import com.andrewcorp.webwallet.user.UserService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final UserService users;
    private final ExchangeRateService exchangeRates;
    private final BudgetService budgetService;
    private final InvestmentValuation investments;
    private final InvestmentOperationRepository investmentOperations;
    private final Clock clock;

    public AccountService(
            AccountRepository accounts,
            TransactionRepository transactions,
            UserService users,
            ExchangeRateService exchangeRates,
            BudgetService budgetService,
            InvestmentValuation investments,
            InvestmentOperationRepository investmentOperations,
            Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.users = users;
        this.exchangeRates = exchangeRates;
        this.budgetService = budgetService;
        this.investments = investments;
        this.investmentOperations = investmentOperations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(Long userId) {
        Map<Long, BigDecimal> balances = balances(userId);
        CurrencyConverter converter = converter(userId);
        return accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(account -> response(
                        account, balances.getOrDefault(account.getId(), account.getOpeningBalance()), converter))
                .toList();
    }

    /** Totals of the accounts that are not archived in the base currency, at today's rates. */
    @Transactional(readOnly = true)
    public AccountSummaryResponse summary(Long userId) {
        Map<Long, BigDecimal> balances = balances(userId);
        CurrencyConverter converter = converter(userId);
        LocalDate today = LocalDate.now(clock);
        BigDecimal netWorth = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Account account : accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            if (account.isArchived()) {
                continue;
            }
            BigDecimal balance = balances.getOrDefault(account.getId(), account.getOpeningBalance());
            BigDecimal converted = converter.convert(balance, account.getCurrency(), today);
            netWorth = netWorth.add(converted);
            switch (account.getType()) {
                case CASH, CARD -> available = available.add(converted);
                case CREDIT_CARD -> {
                    // Own money on the card counts too; a card over its limit adds nothing
                    BigDecimal limit = account.getCreditLimit() != null ? account.getCreditLimit() : BigDecimal.ZERO;
                    BigDecimal spendable = limit.add(balance).max(BigDecimal.ZERO);
                    credit = credit.add(converter.convert(spendable, account.getCurrency(), today));
                }
                default -> {}
            }
        }
        MonthResponse budget = budgetService.month(userId, YearMonth.now(clock));
        TreeSet<String> unconverted = new TreeSet<>(converter.unconverted());
        unconverted.addAll(budget.unconvertedCurrencies());
        return new AccountSummaryResponse(
                converter.target(),
                netWorth,
                available,
                available.add(credit),
                budget.readyToAssign(),
                List.copyOf(unconverted));
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long userId, Long id) {
        return toResponse(userId, find(userId, id));
    }

    @Transactional
    public AccountResponse create(Long userId, AccountRequest request) {
        if (request.currency() == null) {
            throw ApiException.invalid("currency", "error.required");
        }
        Account account = new Account(userId, request.name().trim(), request.type(), request.currency());
        apply(account, request);
        return response(accounts.save(account), account.getOpeningBalance(), converter(userId));
    }

    @Transactional
    public AccountResponse update(Long userId, Long id, AccountRequest request) {
        Account account = find(userId, id);
        account.setName(request.name().trim());
        account.setType(request.type());
        apply(account, request);
        return toResponse(userId, account);
    }

    /** Only accounts without transactions can be deleted; others should be archived. */
    @Transactional
    public void delete(Long userId, Long id) {
        Account account = find(userId, id);
        if (transactions.existsByAccountIdOrToAccountId(account.getId(), account.getId())
                || investmentOperations.existsByAccountId(account.getId())) {
            throw new ApiException(ErrorCode.RESOURCE_IN_USE);
        }
        accounts.delete(account);
    }

    /** Loads an account of the user; accounts of other users are reported as missing. */
    @Transactional(readOnly = true)
    public Account find(Long userId, Long id) {
        return accounts.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    /**
     * Current balance of every account of the user, in the account currency. A brokerage account also holds the
     * cash of its investment operations and the value of its positions, at today's rates.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> balances(Long userId) {
        Map<Long, BigDecimal> balances = ledgerBalances(userId);
        Map<Long, String> brokerage = new HashMap<>();
        for (Account account : accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            if (account.getType() == AccountType.INVESTMENT) {
                brokerage.put(account.getId(), account.getCurrency());
            }
        }
        if (!brokerage.isEmpty()) {
            investments
                    .balances(userId, brokerage, LocalDate.now(clock))
                    .forEach((accountId, amount) -> balances.merge(accountId, amount, BigDecimal::add));
        }
        return balances;
    }

    /**
     * Balance of every account from the transactions alone, in the account currency: what a bank reports for its
     * accounts, without the investments of a brokerage account.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> ledgerBalances(Long userId) {
        Map<Long, BigDecimal> balances = new HashMap<>();
        accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)
                .forEach(account -> balances.put(account.getId(), account.getOpeningBalance()));
        for (AccountChange change : transactions.sourceChanges(userId)) {
            balances.merge(change.getAccountId(), change.getChange(), BigDecimal::add);
        }
        for (AccountChange change : transactions.transferIncome(userId)) {
            balances.merge(change.getAccountId(), change.getChange(), BigDecimal::add);
        }
        return balances;
    }

    /**
     * Sets the opening balance so that the balance computed from the transactions equals the given one, e.g. the
     * balance reported by a bank for an account whose earlier history is not imported.
     */
    @Transactional
    public void alignBalance(Account account, BigDecimal balance) {
        transactions.flush();
        BigDecimal computed =
                ledgerBalances(account.getUserId()).getOrDefault(account.getId(), account.getOpeningBalance());
        account.setOpeningBalance(account.getOpeningBalance().add(balance.subtract(computed)));
    }

    /** Accounts in the currency with any of the card numbers (last four digits), in the display order. */
    @Transactional(readOnly = true)
    public List<Account> findByCardNumbers(Long userId, String currency, Collection<String> cardNumbers) {
        return accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .filter(account -> account.getCurrency().equals(currency))
                .filter(account -> account.getCardNumbers() != null
                        && account.getCardNumbers().stream().anyMatch(cardNumbers::contains))
                .toList();
    }

    private AccountResponse toResponse(Long userId, Account account) {
        return response(
                account,
                balances(userId).getOrDefault(account.getId(), account.getOpeningBalance()),
                converter(userId));
    }

    private AccountResponse response(Account account, BigDecimal balance, CurrencyConverter converter) {
        return AccountResponse.from(
                account, balance, converter.convertIfKnown(balance, account.getCurrency(), LocalDate.now(clock)));
    }

    private CurrencyConverter converter(Long userId) {
        return exchangeRates.converterTo(users.get(userId).getBaseCurrency());
    }

    /**
     * Optional fields that are omitted keep their value (the defaults of a new account), so that a client changing
     * the name does not reset e.g. the opening balance aligned with a bank. A credit limit or interest rate of 0
     * removes it.
     */
    private static void apply(Account account, AccountRequest request) {
        if (request.inBudget() != null) {
            account.setInBudget(request.inBudget());
        }
        if (request.archived() != null) {
            account.setArchived(request.archived());
        }
        if (request.openingBalance() != null) {
            account.setOpeningBalance(request.openingBalance());
        }
        if (request.creditLimit() != null) {
            account.setCreditLimit(request.creditLimit().signum() == 0 ? null : request.creditLimit());
        }
        if (request.interestRate() != null) {
            account.setInterestRate(request.interestRate().signum() == 0 ? null : request.interestRate());
        }
        if (request.sortOrder() != null) {
            account.setSortOrder(request.sortOrder());
        }
    }
}
