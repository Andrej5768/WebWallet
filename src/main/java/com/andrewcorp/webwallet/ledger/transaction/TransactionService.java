package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.web.Periods;
import com.andrewcorp.webwallet.ledger.LedgerLookups;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.rule.RuleService;
import com.andrewcorp.webwallet.ledger.tag.TagService;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionFilter;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionRequest;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionRequest.SplitRequest;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionResponse;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionResponse.RecurringRef;
import com.andrewcorp.webwallet.planning.RecurringOccurrence;
import com.andrewcorp.webwallet.planning.RecurringOccurrenceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final LedgerLookups lookups;
    private final TagService tagService;
    private final RuleService ruleService;
    private final RecurringOccurrenceRepository occurrences;

    public TransactionService(
            TransactionRepository transactions,
            CategoryRepository categories,
            LedgerLookups lookups,
            TagService tagService,
            RuleService ruleService,
            RecurringOccurrenceRepository occurrences) {
        this.transactions = transactions;
        this.categories = categories;
        this.lookups = lookups;
        this.tagService = tagService;
        this.ruleService = ruleService;
        this.occurrences = occurrences;
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(Long userId, TransactionFilter filter, Pageable pageable) {
        Page<Transaction> page = transactions.findAll(
                TransactionSpecifications.matching(userId, filter, categoryIds(userId, filter)), pageable);
        Map<Long, RecurringRef> recurring =
                recurring(page.map(Transaction::getId).getContent());
        return page.map(transaction -> TransactionResponse.from(transaction, recurring.get(transaction.getId())));
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long userId, Long id) {
        return response(find(userId, id));
    }

    /** The response of the transaction, with the recurring payment it pays. */
    @Transactional(readOnly = true)
    public TransactionResponse response(Transaction transaction) {
        return TransactionResponse.from(
                transaction, recurring(List.of(transaction.getId())).get(transaction.getId()));
    }

    @Transactional
    public TransactionResponse create(Long userId, TransactionRequest request) {
        Transaction transaction = new Transaction(userId);
        apply(userId, transaction, request);
        return TransactionResponse.from(transactions.save(transaction), null);
    }

    @Transactional
    public TransactionResponse update(Long userId, Long id, TransactionRequest request) {
        Transaction transaction = find(userId, id);
        Category before = transaction.singleCategory();
        apply(userId, transaction, request);
        Category after = transaction.singleCategory();
        // A corrected category of a payee is applied to its later imported operations
        if (after != null
                && transaction.getPayee() != null
                && (before == null || !before.getId().equals(after.getId()))) {
            ruleService.learn(userId, transaction.getPayee(), after);
        }
        return response(transaction);
    }

    /**
     * Deletes the transaction. The occurrence of a recurring payment it paid becomes pending again, and is not matched
     * or created automatically again.
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Transaction transaction = find(userId, id);
        occurrences.findByTransactionIds(List.of(id)).forEach(RecurringOccurrence::pending);
        occurrences.flush();
        transactions.delete(transaction);
    }

    private Map<Long, RecurringRef> recurring(List<Long> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        return occurrences.findByTransactionIds(transactionIds).stream()
                .collect(Collectors.toMap(
                        occurrence -> occurrence.getTransaction().getId(),
                        occurrence -> new RecurringRef(
                                occurrence.getRecurring().getId(),
                                occurrence.getRecurring().getName(),
                                occurrence.getDueDate())));
    }

    /**
     * The transaction no longer stands for the bank operation; when it was imported as that operation, its external
     * id is released too, so that the operation can be imported again.
     */
    private static void releaseBankId(Transaction transaction, String bankId) {
        if (bankId != null && bankId.equals(transaction.getExternalId())) {
            transaction.setExternalId(null);
        }
    }

    /** The filtered category and its subcategories. */
    private List<Long> categoryIds(Long userId, TransactionFilter filter) {
        if (filter.categoryId() == null) {
            return null;
        }
        List<Long> ids = new ArrayList<>(List.of(filter.categoryId()));
        ids.addAll(categories.findChildIds(userId, filter.categoryId()));
        return ids;
    }

    private Transaction find(Long userId, Long id) {
        return transactions.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    private void apply(Long userId, Transaction transaction, TransactionRequest request) {
        Periods.checkDate(request.date(), "date");
        Account account = lookups.account(userId, request.accountId(), "accountId");
        // A bank operation belongs to its account: moved elsewhere, the transaction no longer stands for it, and the
        // operation must be imported again
        if (transaction.getAccount() != null
                && !transaction.getAccount().getId().equals(account.getId())) {
            releaseBankId(transaction, transaction.getBankId());
            transaction.setBankId(null);
        }
        // Edited by the user: the bank no longer changes or deletes it
        transaction.setHold(false);
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

        transaction.setPayee(lookups.payee(userId, request.payeeId(), request.payeeName()));
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
        Account toAccount = lookups.account(userId, request.toAccountId(), "toAccountId");
        BigDecimal toAmount = request.toAmount();
        if (toAmount == null) {
            if (!toAccount.getCurrency().equals(account.getCurrency())) {
                throw ApiException.invalid("toAmount", "error.required");
            }
            toAmount = request.amount();
        }
        if (transaction.getToAccount() != null
                && !transaction.getToAccount().getId().equals(toAccount.getId())) {
            releaseBankId(transaction, transaction.getToBankId());
            transaction.setToBankId(null);
            // The incoming half of another account can be imported again
            transaction.setToExternalId(null);
        }
        transaction.setToAccount(toAccount);
        transaction.setToAmount(toAmount);
        transaction.replaceSplits(List.of());
    }

    private void applyExpenseOrIncome(Long userId, Transaction transaction, TransactionRequest request) {
        if (request.toAccountId() != null || request.toAmount() != null) {
            throw ApiException.invalid("toAccountId", "error.transaction.transferOnly");
        }
        CategoryKind kind = CategoryKind.of(request.type());
        List<SplitData> splits = new ArrayList<>();
        if (request.splits() == null || request.splits().isEmpty()) {
            splits.add(new SplitData(
                    lookups.category(userId, request.categoryId(), kind, "categoryId"), request.amount(), null));
        } else {
            if (request.categoryId() != null) {
                throw ApiException.invalid("categoryId", "error.transaction.categoryAndSplits");
            }
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < request.splits().size(); i++) {
                SplitRequest split = request.splits().get(i);
                splits.add(new SplitData(
                        lookups.category(userId, split.categoryId(), kind, "splits[" + i + "].categoryId"),
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
        // No longer a transfer: the incoming half is a separate operation again
        releaseBankId(transaction, transaction.getToBankId());
        transaction.setToBankId(null);
        transaction.setToExternalId(null);
        transaction.replaceSplits(splits);
    }
}
