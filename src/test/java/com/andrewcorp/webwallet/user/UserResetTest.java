package com.andrewcorp.webwallet.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.bank.BankConnection;
import com.andrewcorp.webwallet.bank.BankConnectionRepository;
import com.andrewcorp.webwallet.bank.BankProvider;
import com.andrewcorp.webwallet.budget.BudgetAssignment;
import com.andrewcorp.webwallet.budget.BudgetRepository;
import com.andrewcorp.webwallet.imports.preview.ImportPreview;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewRepository;
import com.andrewcorp.webwallet.imports.preview.ImportSource;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeRepository;
import com.andrewcorp.webwallet.ledger.rule.CategorizationRule;
import com.andrewcorp.webwallet.ledger.rule.CategorizationRuleRepository;
import com.andrewcorp.webwallet.ledger.tag.Tag;
import com.andrewcorp.webwallet.ledger.tag.TagRepository;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UserResetTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private PayeeRepository payees;

    @Autowired
    private TagRepository tags;

    @Autowired
    private CategorizationRuleRepository rules;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private BudgetRepository budget;

    @Autowired
    private BankConnectionRepository connections;

    @Autowired
    private ImportPreviewRepository previews;

    private User user;
    private String auth;
    private User other;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        other = createVerifiedUser();
        fillData(user);
        fillData(other);
    }

    /** One of everything that "start over" deletes or keeps. */
    private void fillData(User owner) {
        Long userId = owner.getId();
        Account card = accounts.save(new Account(userId, "Card", AccountType.CARD, "UAH"));
        Category groceries = categories.save(new Category(userId, null, "My groceries", CategoryKind.EXPENSE));
        Payee shop = payees.save(new Payee(userId, "Shop"));
        payees.save(new Payee(userId, "Unused"));
        Tag tag = tags.save(new Tag(userId, "trip"));
        CategorizationRule byPayee = new CategorizationRule(userId, groceries);
        byPayee.setPayee(shop);
        byPayee.setLearned(true);
        rules.save(byPayee);
        CategorizationRule byMcc = new CategorizationRule(userId, groceries);
        byMcc.setMcc((short) 5411);
        rules.save(byMcc);

        Transaction expense = new Transaction(userId);
        expense.setType(TransactionType.EXPENSE);
        expense.setDate(LocalDate.of(2026, 9, 1));
        expense.setAccount(card);
        expense.setAmount(new BigDecimal("100"));
        expense.setPayee(shop);
        expense.replaceSplits(List.of(new SplitData(groceries, new BigDecimal("100"), null)));
        expense.replaceTags(Set.of(tag));
        transactions.save(expense);
        budget.save(new BudgetAssignment(userId, LocalDate.of(2026, 9, 1), groceries, new BigDecimal("500")));
        connections.save(new BankConnection(userId, BankProvider.MONOBANK, "token", "secret-" + userId));
        Instant now = Instant.now();
        previews.save(new ImportPreview(userId, ImportSource.ZENMONEY, "{}", "{}", now, now.plus(Duration.ofHours(1))));
    }

    private void assertUntouched(User owner) {
        Long userId = owner.getId();
        assertThat(accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)).hasSize(1);
        assertThat(categories.findByUserIdOrderBySortOrderAscNameAsc(userId))
                .extracting(Category::getName)
                .containsExactly("My groceries");
        assertThat(payees.findByUserIdOrderByNameAsc(userId)).hasSize(2);
        assertThat(tags.findByUserIdOrderByNameAsc(userId)).hasSize(1);
        assertThat(rules.findByUserIdOrderByIdAsc(userId)).hasSize(2);
        assertThat(transactions.findAll())
                .filteredOn(t -> t.getUserId().equals(userId))
                .hasSize(1);
        assertThat(budget.existsByUserId(userId)).isTrue();
        assertThat(connections.findByUserIdAndProvider(userId, BankProvider.MONOBANK))
                .isPresent();
        assertThat(previews.findIdsNewestFirst(userId)).hasSize(1);
    }

    private void assertFinanceDataDeleted(Long userId) {
        assertThat(accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)).isEmpty();
        assertThat(tags.findByUserIdOrderByNameAsc(userId)).isEmpty();
        assertThat(transactions.findAll()).noneMatch(t -> t.getUserId().equals(userId));
        assertThat(budget.existsByUserId(userId)).isFalse();
        assertThat(connections.findByUserIdAndProvider(userId, BankProvider.MONOBANK))
                .isEmpty();
        assertThat(previews.findIdsNewestFirst(userId)).isEmpty();
    }

    @Test
    void requiresTheExactConfirmation() throws Exception {
        mockMvc.perform(postJson("/api/v1/me/reset", Map.of("keepCategories", false), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("confirm"));
        mockMvc.perform(postJson("/api/v1/me/reset", Map.of("keepCategories", false, "confirm", "delete"), auth))
                .andExpect(status().isBadRequest());
        mockMvc.perform(postJson("/api/v1/me/reset", Map.of("confirm", "DELETE")))
                .andExpect(status().isUnauthorized());

        assertUntouched(user);
    }

    @Test
    void startOverWithDefaultCategories() throws Exception {
        mockMvc.perform(postJson("/api/v1/me/reset", Map.of("keepCategories", false, "confirm", "DELETE"), auth))
                .andExpect(status().isNoContent());

        assertFinanceDataDeleted(user.getId());
        assertThat(payees.findByUserIdOrderByNameAsc(user.getId())).isEmpty();
        assertThat(rules.findByUserIdOrderByIdAsc(user.getId())).isEmpty();
        // The categories are the default ones again, in the language of the user
        assertThat(categories.findByUserIdOrderBySortOrderAscNameAsc(user.getId()))
                .isNotEmpty()
                .extracting(Category::getName)
                .contains("Groceries")
                .doesNotContain("My groceries");
        // The profile and the session stay
        mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()));
        assertUntouched(other);
    }

    @Test
    void startOverKeepingCategoriesAndRules() throws Exception {
        mockMvc.perform(postJson("/api/v1/me/reset", Map.of("keepCategories", true, "confirm", "DELETE"), auth))
                .andExpect(status().isNoContent());

        assertFinanceDataDeleted(user.getId());
        assertThat(categories.findByUserIdOrderBySortOrderAscNameAsc(user.getId()))
                .extracting(Category::getName)
                .containsExactly("My groceries");
        assertThat(rules.findByUserIdOrderByIdAsc(user.getId())).hasSize(2);
        // The payee of a kept rule stays
        assertThat(payees.findByUserIdOrderByNameAsc(user.getId()))
                .extracting(Payee::getName)
                .containsExactly("Shop");
        assertUntouched(other);
    }
}
