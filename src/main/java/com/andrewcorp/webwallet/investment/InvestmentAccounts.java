package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Which brokerage accounts a view of the investments covers. */
@Component
public class InvestmentAccounts {

    private final AccountRepository accounts;

    public InvestmentAccounts(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * The brokerage accounts of the user by id, in their display order: the given one (archived or not), or all that
     * are not archived. An unknown account is not found.
     */
    public Map<Long, Account> scope(Long userId, Long accountId) {
        Map<Long, Account> scope = new LinkedHashMap<>();
        for (Account account : accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            boolean wanted = accountId != null ? account.getId().equals(accountId) : !account.isArchived();
            if (account.getType() == AccountType.INVESTMENT && wanted) {
                scope.put(account.getId(), account);
            }
        }
        if (accountId != null && scope.isEmpty()) {
            throw ApiException.notFound();
        }
        return scope;
    }
}
