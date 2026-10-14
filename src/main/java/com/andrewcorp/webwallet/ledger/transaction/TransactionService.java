package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeRepository;
import com.andrewcorp.webwallet.ledger.payee.PayeeService;
import com.andrewcorp.webwallet.ledger.rule.RuleService;
import com.andrewcorp.webwallet.ledger.tag.TagService;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionFilter;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionRequest;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionRequest.SplitRequest;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final PayeeRepository payees;
    private final PayeeService payeeService;
    private final TagService tagService;
    private final RuleService ruleService;

    public TransactionService(
            TransactionRepository transactions,
            AccountRepository accounts,
            CategoryRepository categories,
            PayeeRepository payees,
            PayeeService payeeService,
            TagService tagService,
            RuleService ruleService) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.payees = payees;
        this.payeeService = payeeService;
        this.tagService = tagService;
        this.ruleService = ruleService;
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(Long userId, TransactionFilter filter, Pageable pageable) {
        return transactions
                .findAll(TransactionSpecifications.matching(userId, filter), pageable)
                .map(TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long userId, Long id) {
        return TransactionResponse.from(find(userId, id));
    }

    @Transactional
    public TransactionResponse create(Long userId, TransactionRequest request) {
        Transaction transaction = new Transaction(userId);
        apply(userId, transaction, request);
        return TransactionResponse.from(transactions.save(transaction));
    }

    @Transactional
    public TransactionResponse update(Long userId, Long id, TransactionRequest request) {
        Transaction transaction = find(userId, id);
        Category before = singleCategory(transaction);
        apply(userId, transaction, request);
        Category after = singleCategory(transaction);
        // A corrected category of a payee is applied to its later imported operations
        if (after != null
                && transaction.getPayee() != null
                && (before == null || !before.getId().equals(after.getId()))) {
            ruleService.learn(userId, transaction.getPayee(), after);
        }
        return TransactionResponse.from(transaction);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        transactions.delete(find(userId, id));
    }

    private static Category singleCategory(Transaction transaction) {
        return transaction.getSplits().size() == 1
                ? transaction.getSplits().get(0).getCategory()
                : null;
    }

    private Transaction find(Long userId, Long id) {
        return transactions.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    private void apply(Long userId, Transaction transaction, TransactionRequest request) {
        Account account = account(userId, request.accountId(), "accountId");
        transaction.setType(request.type());
        transaction.setDate(request.date());
        transaction.setAccount(account);
        transaction.setAmount(request.amount());

        if ((request.originalAmount() == null) != (request.originalCurrency() == null)) {
            throw ApiException.invalid("originalCurrency", "error.transaction.original");
        }
        transaction.setOriginalAmount(request.originalAmount());
        transaction.setOriginalCurrency(request.originalCurrency());

        if (request.type() == TransactionType.TRANSFER) {
            applyTransfer(userId, transaction, request, account);
        } else {
            applyExpenseOrIncome(userId, transaction, request);
        }

        transaction.setPayee(payee(userId, request));
        transaction.setComment(request.comment());
        transaction.replaceTags(tagService.findAll(userId, request.tagIds(), "tagIds"));
    }

    private void applyTransfer(Long userId, Transaction transaction, TransactionRequest request, Account account) {
        if (request.toAccountId() == null) {
            throw ApiException.invalid("toAccountId", "error.required");
        }
        if (Objects.equals(request.toAccountId(), request.accountId())) {
            throw ApiException.invalid("toAccountId", "error.transaction.sameAccount");
        }
        if (request.categoryId() != null
                || (request.splits() != null && !request.splits().isEmpty())) {
            throw ApiException.invalid("splits", "error.transaction.transferCategory");
        }
        Account toAccount = account(userId, request.toAccountId(), "toAccountId");
        BigDecimal toAmount = request.toAmount();
        if (toAmount == null) {
            if (!toAccount.getCurrency().equals(account.getCurrency())) {
                throw ApiException.invalid("toAmount", "error.required");
            }
            toAmount = request.amount();
        }
        transaction.setToAccount(toAccount);
        transaction.setToAmount(toAmount);
        transaction.replaceSplits(List.of());
    }

    private void applyExpenseOrIncome(Long userId, Transaction transaction, TransactionRequest request) {
        if (request.toAccountId() != null || request.toAmount() != null) {
            throw ApiException.invalid("toAccountId", "error.transaction.transferOnly");
        }
        CategoryKind kind = request.type() == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        List<SplitData> splits = new ArrayList<>();
        if (request.splits() == null || request.splits().isEmpty()) {
            splits.add(
                    new SplitData(category(userId, request.categoryId(), kind, "categoryId"), request.amount(), null));
        } else {
            if (request.categoryId() != null) {
                throw ApiException.invalid("categoryId", "error.transaction.categoryAndSplits");
            }
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < request.splits().size(); i++) {
                SplitRequest split = request.splits().get(i);
                splits.add(new SplitData(
                        category(userId, split.categoryId(), kind, "splits[" + i + "].categoryId"),
                        split.amount(),
                        split.comment()));
                total = total.add(split.amount());
            }
            if (total.compareTo(request.amount()) != 0) {
                throw ApiException.invalid("splits", "error.transaction.splitSum");
            }
        }
        transaction.setToAccount(null);
        transaction.setToAmount(null);
        transaction.replaceSplits(splits);
    }

    private Account account(Long userId, Long id, String field) {
        return accounts.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.invalid(field, "error.notFound"));
    }

    /** Null id means uncategorized. */
    private Category category(Long userId, Long id, CategoryKind kind, String field) {
        if (id == null) {
            return null;
        }
        Category category = categories
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.invalid(field, "error.notFound"));
        if (category.getKind() != kind) {
            throw ApiException.invalid(field, "error.category.kind");
        }
        return category;
    }

    private Payee payee(Long userId, TransactionRequest request) {
        if (request.payeeId() != null) {
            return payees.findByIdAndUserId(request.payeeId(), userId)
                    .orElseThrow(() -> ApiException.invalid("payeeId", "error.notFound"));
        }
        if (request.payeeName() != null && !request.payeeName().isBlank()) {
            return payeeService.findOrCreate(userId, request.payeeName());
        }
        return null;
    }
}
