package com.andrewcorp.webwallet.ledger.rule;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeRepository;
import com.andrewcorp.webwallet.ledger.rule.RuleDtos.RuleRequest;
import com.andrewcorp.webwallet.ledger.rule.RuleDtos.RuleResponse;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSplit;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class RuleService {

    private final CategorizationRuleRepository rules;
    private final CategoryRepository categories;
    private final PayeeRepository payees;
    private final TransactionRepository transactions;
    private final CategorySuggester suggester;

    public RuleService(CategorizationRuleRepository rules, CategoryRepository categories, PayeeRepository payees,
                       TransactionRepository transactions, CategorySuggester suggester) {
        this.rules = rules;
        this.categories = categories;
        this.payees = payees;
        this.transactions = transactions;
        this.suggester = suggester;
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> list(Long userId) {
        return rules.findByUserIdOrderByIdAsc(userId).stream().map(RuleResponse::from).toList();
    }

    @Transactional
    public RuleResponse create(Long userId, RuleRequest request) {
        CategorizationRule rule = new CategorizationRule(userId, category(userId, request.categoryId()));
        apply(userId, rule, request);
        return RuleResponse.from(rules.save(rule));
    }

    @Transactional
    public RuleResponse update(Long userId, Long id, RuleRequest request) {
        CategorizationRule rule = rules.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
        rule.setCategory(category(userId, request.categoryId()));
        apply(userId, rule, request);
        // Edited by the user, so it is not a learned rule any more
        rule.setLearned(false);
        return RuleResponse.from(rule);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        rules.delete(rules.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound));
    }

    /**
     * Remembers a manual change of the category of a transaction with a payee, so that later operations of the payee
     * get the same category.
     */
    @Transactional
    public void learn(Long userId, Payee payee, Category category) {
        CategorizationRule rule = rules.findLearnedForPayee(userId, payee).orElseGet(() -> {
            CategorizationRule created = new CategorizationRule(userId, category);
            created.setPayee(payee);
            created.setLearned(true);
            return created;
        });
        rule.setCategory(category);
        rules.save(rule);
    }

    /** Puts uncategorized expenses and incomes into categories by the rules. */
    @Transactional
    public int applyToUncategorized(Long userId) {
        List<CategorizationRule> userRules = rules.findByUserIdOrderByIdAsc(userId);
        if (userRules.isEmpty()) {
            return 0;
        }
        int categorized = 0;
        for (Transaction transaction : transactions.findUncategorized(userId)) {
            CategoryKind kind = transaction.getType() == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
            Optional<Category> category = suggester.byRule(userRules, transaction.getPayee(), transaction.getMcc(),
                    transaction.getBankCategory(), kind);
            if (category.isPresent()) {
                TransactionSplit split = transaction.getSplits().get(0);
                transaction.replaceSplits(List.of(new SplitData(category.get(), split.getAmount(), split.getComment())));
                categorized++;
            }
        }
        return categorized;
    }

    private void apply(Long userId, CategorizationRule rule, RuleRequest request) {
        boolean hasCondition = request.payeeId() != null || notBlank(request.payeePattern()) || request.mcc() != null
                || notBlank(request.bankCategory());
        if (!hasCondition) {
            throw ApiException.invalid("payeeId", "error.rule.condition");
        }
        rule.setPayee(request.payeeId() == null ? null : payees.findByIdAndUserId(request.payeeId(), userId)
                .orElseThrow(() -> ApiException.invalid("payeeId", "error.notFound")));
        rule.setPayeePattern(notBlank(request.payeePattern()) ? request.payeePattern().trim() : null);
        rule.setMcc(request.mcc());
        rule.setBankCategory(notBlank(request.bankCategory()) ? request.bankCategory().trim() : null);
    }

    private Category category(Long userId, Long id) {
        return categories.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.invalid("categoryId", "error.notFound"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
