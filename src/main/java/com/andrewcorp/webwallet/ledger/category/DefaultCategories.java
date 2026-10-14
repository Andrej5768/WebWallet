package com.andrewcorp.webwallet.ledger.category;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Starter set of categories created for a new user, named in the language of the user.
 */
@Component
public class DefaultCategories {

    private static final Map<String, List<String>> EXPENSES = new LinkedHashMap<>();
    private static final List<String> INCOME = List.of("salary", "gifts", "interest", "otherIncome");

    static {
        EXPENSES.put("food", List.of("groceries", "restaurants"));
        EXPENSES.put("housing", List.of("rent", "utilities", "repairs"));
        EXPENSES.put("transport", List.of("publicTransport", "taxi", "fuel"));
        EXPENSES.put("health", List.of());
        EXPENSES.put("shopping", List.of("clothing", "household"));
        EXPENSES.put("entertainment", List.of("subscriptions"));
        EXPENSES.put("education", List.of());
        EXPENSES.put("pets", List.of());
        EXPENSES.put("donations", List.of());
        EXPENSES.put("fees", List.of());
    }

    private final CategoryRepository categories;
    private final MessageSource messages;

    public DefaultCategories(CategoryRepository categories, MessageSource messages) {
        this.categories = categories;
        this.messages = messages;
    }

    @Transactional
    public void create(Long userId, Locale locale) {
        int order = 0;
        for (Map.Entry<String, List<String>> entry : EXPENSES.entrySet()) {
            Category parent = save(userId, null, entry.getKey(), CategoryKind.EXPENSE, order++, locale);
            int childOrder = 0;
            for (String child : entry.getValue()) {
                save(userId, parent.getId(), child, CategoryKind.EXPENSE, childOrder++, locale);
            }
        }
        for (String key : INCOME) {
            save(userId, null, key, CategoryKind.INCOME, order++, locale);
        }
    }

    private Category save(Long userId, Long parentId, String key, CategoryKind kind, int order, Locale locale) {
        Category category = new Category(userId, parentId, messages.getMessage("category.default." + key, null, locale), kind);
        category.setSortOrder(order);
        return categories.save(category);
    }
}
