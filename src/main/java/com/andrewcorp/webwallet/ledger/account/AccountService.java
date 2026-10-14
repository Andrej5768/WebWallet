package com.andrewcorp.webwallet.ledger.account;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.ledger.account.dto.AccountRequest;
import com.andrewcorp.webwallet.ledger.account.dto.AccountResponse;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository.AccountChange;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public AccountService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(Long userId) {
        Map<Long, BigDecimal> balances = balances(userId);
        return accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(account -> AccountResponse.from(
                        account, balances.getOrDefault(account.getId(), account.getOpeningBalance())))
                .toList();
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
        return AccountResponse.from(accounts.save(account), account.getOpeningBalance());
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
        if (transactions.existsByAccountIdOrToAccountId(account.getId(), account.getId())) {
            throw new ApiException(ErrorCode.RESOURCE_IN_USE);
        }
        accounts.delete(account);
    }

    /** Loads an account of the user; accounts of other users are reported as missing. */
    @Transactional(readOnly = true)
    public Account find(Long userId, Long id) {
        return accounts.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    /** Current balance of every account of the user, in the account currency. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> balances(Long userId) {
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
        BigDecimal computed = balances(account.getUserId()).getOrDefault(account.getId(), account.getOpeningBalance());
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
        return AccountResponse.from(
                account, balances(userId).getOrDefault(account.getId(), account.getOpeningBalance()));
    }

    private static void apply(Account account, AccountRequest request) {
        account.setInBudget(
                request.inBudget() != null ? request.inBudget() : request.type().inBudgetByDefault());
        account.setArchived(Boolean.TRUE.equals(request.archived()));
        account.setOpeningBalance(request.openingBalance() != null ? request.openingBalance() : BigDecimal.ZERO);
        account.setCreditLimit(request.creditLimit());
        account.setInterestRate(request.interestRate());
        account.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
    }
}
