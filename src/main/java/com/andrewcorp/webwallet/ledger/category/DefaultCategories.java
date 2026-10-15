package com.andrewcorp.webwallet.ledger.category;

import static com.andrewcorp.webwallet.ledger.category.Necessity.NEEDS;
import static com.andrewcorp.webwallet.ledger.category.Necessity.WANTS;

import com.andrewcorp.webwallet.ledger.PaletteColor;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starter set of categories created for a new user, named in the language of the user. Subcategories take the color
 * of their group; a subcategory without a necessity has the one of its parent.
 */
@Component
public class DefaultCategories {

    private static final List<Group> EXPENSES = List.of(
            new Group(
                    "food",
                    "utensils",
                    PaletteColor.ORANGE,
                    NEEDS,
                    List.of(new Item("groceries", "shopping-basket", null), new Item("restaurants", "coffee", WANTS))),
            new Group(
                    "housing",
                    "house",
                    PaletteColor.TEAL,
                    NEEDS,
                    List.of(
                            new Item("rent", "key-round", null),
                            new Item("utilities", "zap", null),
                            new Item("repairs", "wrench", null))),
            new Group(
                    "transport",
                    "bus",
                    PaletteColor.BLUE,
                    NEEDS,
                    List.of(
                            new Item("publicTransport", "tram-front", null),
                            new Item("taxi", "car-taxi-front", WANTS),
                            new Item("fuel", "fuel", null))),
            new Group("health", "heart-pulse", PaletteColor.PINK, NEEDS, List.of()),
            new Group(
                    "shopping",
                    "shopping-bag",
                    PaletteColor.AMBER,
                    WANTS,
                    List.of(new Item("clothing", "shirt", null), new Item("household", "sofa", NEEDS))),
            new Group(
                    "entertainment",
                    "popcorn",
                    PaletteColor.VIOLET,
                    WANTS,
                    List.of(new Item("subscriptions", "tv", null))),
            new Group("education", "graduation-cap", PaletteColor.INDIGO, NEEDS, List.of()),
            new Group("pets", "paw-print", PaletteColor.INDIGO, NEEDS, List.of()),
            new Group("donations", "gift", PaletteColor.RED, WANTS, List.of()),
            new Group("fees", "percent", PaletteColor.SLATE, NEEDS, List.of()));

    private static final List<Item> INCOME = List.of(
            new Item("salary", "briefcase", null),
            new Item("gifts", "gift", null),
            new Item("interest", "piggy-bank", null),
            new Item("otherIncome", "coins", null));

    private final CategoryRepository categories;
    private final MessageSource messages;

    public DefaultCategories(CategoryRepository categories, MessageSource messages) {
        this.categories = categories;
        this.messages = messages;
    }

    @Transactional
    public void create(Long userId, Locale locale) {
        int order = 0;
        for (Group group : EXPENSES) {
            Category parent = save(
                    userId,
                    null,
                    new Item(group.key(), group.icon(), group.necessity()),
                    group.color(),
                    CategoryKind.EXPENSE,
                    order++,
                    locale);
            int childOrder = 0;
            for (Item child : group.children()) {
                save(userId, parent.getId(), child, group.color(), CategoryKind.EXPENSE, childOrder++, locale);
            }
        }
        for (Item income : INCOME) {
            save(userId, null, income, PaletteColor.GREEN, CategoryKind.INCOME, order++, locale);
        }
    }

    private Category save(
            Long userId, Long parentId, Item item, PaletteColor color, CategoryKind kind, int order, Locale locale) {
        Category category = new Category(
                userId, parentId, messages.getMessage("category.default." + item.key(), null, locale), kind);
        category.setSortOrder(order);
        category.setIcon(item.icon());
        category.setColor(color);
        category.setNecessity(item.necessity());
        return categories.save(category);
    }

    /** @param key message key of the name, after {@code category.default.} */
    private record Item(String key, String icon, Necessity necessity) {}

    private record Group(String key, String icon, PaletteColor color, Necessity necessity, List<Item> children) {}
}
