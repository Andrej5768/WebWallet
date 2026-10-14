package com.andrewcorp.webwallet.ledger.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CategorySuggesterTest {

    private final CategorizationRuleRepository rules = mock(CategorizationRuleRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final CategorySuggester suggester = new CategorySuggester(rules, transactions);

    private long nextId = 1;

    private <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", nextId++);
        return entity;
    }

    private Category category(String name, CategoryKind kind) {
        return withId(new Category(1L, null, name, kind));
    }

    private CategorizationRule rule(Category category) {
        return withId(new CategorizationRule(1L, category));
    }

    @Test
    void mostSpecificMatchingRuleWins() {
        Payee silpo = withId(new Payee(1L, "Silpo Market"));
        Category groceries = category("Groceries", CategoryKind.EXPENSE);
        Category food = category("Food", CategoryKind.EXPENSE);
        Category restaurants = category("Restaurants", CategoryKind.EXPENSE);
        Category refunds = category("Refunds", CategoryKind.INCOME);

        CategorizationRule byMcc = rule(groceries);
        byMcc.setMcc((short) 5411);
        CategorizationRule byPattern = rule(food);
        byPattern.setPayeePattern("SILPO");
        CategorizationRule byPayee = rule(restaurants);
        byPayee.setPayee(silpo);
        CategorizationRule incomeByPayee = rule(refunds);
        incomeByPayee.setPayee(silpo);
        when(rules.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(byMcc, byPattern, byPayee, incomeByPayee));

        assertThat(suggester.session(1L).suggest(silpo, (short) 5411, null, CategoryKind.EXPENSE))
                .isSameAs(restaurants);
        assertThat(suggester.session(1L).suggest(silpo, (short) 5411, null, CategoryKind.INCOME))
                .isSameAs(refunds);

        Payee otherSilpo = withId(new Payee(1L, "silpo online"));
        assertThat(suggester.session(1L).suggest(otherSilpo, (short) 5411, null, CategoryKind.EXPENSE))
                .isSameAs(food);
        assertThat(suggester.session(1L).suggest(null, (short) 5411, null, CategoryKind.EXPENSE))
                .isSameAs(groceries);
    }

    @Test
    void allConditionsMustMatchAndArchivedCategoriesAreIgnored() {
        Category transport = category("Transport", CategoryKind.EXPENSE);
        CategorizationRule rule = rule(transport);
        rule.setMcc((short) 4121);
        rule.setBankCategory("Таксі");
        Category archived = category("Old", CategoryKind.EXPENSE);
        archived.setArchived(true);
        CategorizationRule archivedRule = rule(archived);
        archivedRule.setMcc((short) 4121);
        when(rules.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(rule, archivedRule));

        assertThat(suggester.session(1L).suggest(null, (short) 4121, "таксі", CategoryKind.EXPENSE))
                .isSameAs(transport);
        assertThat(suggester.session(1L).suggest(null, (short) 4121, "Інше", CategoryKind.EXPENSE))
                .isNull();
    }

    @Test
    void fallsBackToTheHistoryOfThePayee() {
        Payee payee = withId(new Payee(1L, "Novus"));
        Category groceries = category("Groceries", CategoryKind.EXPENSE);
        when(rules.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(transactions.findCategoriesUsedForPayee(eq(1L), eq(payee), eq(CategoryKind.EXPENSE), any()))
                .thenReturn(List.of(groceries));

        assertThat(suggester.session(1L).suggest(payee, null, null, CategoryKind.EXPENSE))
                .isSameAs(groceries);
        assertThat(suggester.session(1L).suggest(null, null, null, CategoryKind.EXPENSE))
                .isNull();
    }
}
