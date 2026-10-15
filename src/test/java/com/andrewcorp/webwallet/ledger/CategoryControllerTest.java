package com.andrewcorp.webwallet.ledger;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoryControllerTest extends AbstractIntegrationTest {

    private String auth;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
    }

    private Map<String, Object> category(String name, String kind, Long parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("kind", kind);
        body.put("parentId", parentId);
        return body;
    }

    @Test
    void registrationCreatesDefaultCategoriesInUserLanguage() throws Exception {
        mockMvc.perform(postJson(
                                "/api/v1/auth/register",
                                Map.of(
                                        "email",
                                        "cat@test.com",
                                        "password",
                                        PASSWORD,
                                        "firstName",
                                        "A",
                                        "lastName",
                                        "B"))
                        .header("Accept-Language", "uk"))
                .andExpect(status().isAccepted());
        var user = users.findByEmail("cat@test.com").orElseThrow();
        user.setEmailVerified(true);
        users.save(user);
        String ukAuth = bearer(login("cat@test.com", PASSWORD));

        mockMvc.perform(get("/api/v1/categories").header("Authorization", ukAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Їжа"))
                .andExpect(jsonPath("$[0].kind").value("EXPENSE"))
                .andExpect(jsonPath("$[0].children[0].name").value("Продукти"))
                .andExpect(jsonPath("$[?(@.name == 'Зарплата')].kind", contains("INCOME")))
                // Icons, colors and needs / wants; subcategories take the color of their group
                .andExpect(jsonPath("$[0].icon").value("utensils"))
                .andExpect(jsonPath("$[0].color").value("orange"))
                .andExpect(jsonPath("$[0].necessity").value("NEEDS"))
                .andExpect(jsonPath("$[0].children[0].color").value("orange"))
                .andExpect(jsonPath("$[0].children[0].necessity").doesNotExist())
                .andExpect(jsonPath("$[0].children[1].necessity").value("WANTS"))
                .andExpect(jsonPath("$[?(@.name == 'Зарплата')].color", contains("green")));
    }

    @Test
    void iconColorAndNecessity() throws Exception {
        Map<String, Object> body = category("Hobby", "EXPENSE", null);
        body.put("icon", "shopping-cart");
        body.put("color", "violet");
        body.put("necessity", "WANTS");
        Long id = id(json(postJson("/api/v1/categories", body, auth), 201));

        // Omitted fields keep their values
        mockMvc.perform(putJson("/api/v1/categories/" + id, category("Hobbies", "EXPENSE", null), auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hobbies"))
                .andExpect(jsonPath("$.icon").value("shopping-cart"))
                .andExpect(jsonPath("$.color").value("violet"))
                .andExpect(jsonPath("$.necessity").value("WANTS"));
        // Sent as null, they are removed
        Map<String, Object> cleared = category("Hobbies", "EXPENSE", null);
        cleared.put("icon", null);
        cleared.put("color", null);
        cleared.put("necessity", null);
        mockMvc.perform(putJson("/api/v1/categories/" + id, cleared, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icon").doesNotExist())
                .andExpect(jsonPath("$.color").doesNotExist())
                .andExpect(jsonPath("$.necessity").doesNotExist());

        Map<String, Object> badIcon = category("X", "EXPENSE", null);
        badIcon.put("icon", "Shopping Cart");
        mockMvc.perform(postJson("/api/v1/categories", badIcon, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("icon"));
        Map<String, Object> badColor = category("X", "EXPENSE", null);
        badColor.put("color", "purple");
        mockMvc.perform(postJson("/api/v1/categories", badColor, auth)).andExpect(status().isBadRequest());
    }

    @Test
    void onlyExpenseCategoriesAreNeedsOrWants() throws Exception {
        Map<String, Object> income = category("Salary", "INCOME", null);
        income.put("necessity", "NEEDS");
        mockMvc.perform(postJson("/api/v1/categories", income, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("necessity"))
                .andExpect(jsonPath("$.errors[0].message").value("Only expense categories can be needs or wants"));

        // Turned into income, a category and its subcategories lose the flag
        Map<String, Object> parent = category("Side jobs", "EXPENSE", null);
        parent.put("necessity", "NEEDS");
        Long id = id(json(postJson("/api/v1/categories", parent, auth), 201));
        Map<String, Object> child = category("Design", "EXPENSE", id);
        child.put("necessity", "WANTS");
        json(postJson("/api/v1/categories", child, auth), 201);
        mockMvc.perform(putJson("/api/v1/categories/" + id, category("Side jobs", "INCOME", null), auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.necessity").doesNotExist())
                .andExpect(jsonPath("$.children[0].necessity").doesNotExist());
    }

    @Test
    void reorderMovesAndSortsCategories() throws Exception {
        Long food = id(json(postJson("/api/v1/categories", category("Food", "EXPENSE", null), auth), 201));
        Long cafes = id(json(postJson("/api/v1/categories", category("Cafes", "EXPENSE", food), auth), 201));
        Long home = id(json(postJson("/api/v1/categories", category("Home", "EXPENSE", null), auth), 201));
        Long rent = id(json(postJson("/api/v1/categories", category("Rent", "EXPENSE", null), auth), 201));

        // Cafes becomes top-level, rent moves under home, home goes first
        mockMvc.perform(putJson(
                        "/api/v1/categories/order",
                        List.of(
                                order(home, null, 0),
                                order(rent, home, 0),
                                order(food, null, 1),
                                order(cafes, null, 2)),
                        auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(home.intValue(), food.intValue(), cafes.intValue())))
                .andExpect(jsonPath("$[0].children[*].id", contains(rent.intValue())))
                .andExpect(jsonPath("$[1].children").isEmpty());
    }

    @Test
    void reorderKeepsTheRulesAndAppliesAllOrNothing() throws Exception {
        Long food = id(json(postJson("/api/v1/categories", category("Food", "EXPENSE", null), auth), 201));
        Long cafes = id(json(postJson("/api/v1/categories", category("Cafes", "EXPENSE", food), auth), 201));
        Long home = id(json(postJson("/api/v1/categories", category("Home", "EXPENSE", null), auth), 201));
        Long salary = id(json(postJson("/api/v1/categories", category("Salary", "INCOME", null), auth), 201));
        Long foreign =
                id(json(postJson("/api/v1/categories", category("Foreign", "EXPENSE", null), loginNewUser()), 201));

        // A category with subcategories cannot get a parent, even when the first item is valid
        mockMvc.perform(putJson("/api/v1/categories/order", List.of(order(home, null, 5), order(food, home, 0)), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("[1].parentId"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("A category with subcategories cannot become a subcategory"));
        // A parent cannot become a child of its own child
        mockMvc.perform(putJson("/api/v1/categories/order", List.of(order(food, cafes, 0)), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("[0].parentId"));
        // Only two levels
        mockMvc.perform(putJson("/api/v1/categories/order", List.of(order(home, cafes, 0)), auth))
                .andExpect(status().isBadRequest());
        // The kind must match the new parent
        mockMvc.perform(putJson("/api/v1/categories/order", List.of(order(salary, home, 0)), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("The category kind does not match"));
        // Categories of other users are not found, neither as items nor as parents
        mockMvc.perform(putJson("/api/v1/categories/order", List.of(order(foreign, null, 0)), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("[0].id"));
        mockMvc.perform(putJson("/api/v1/categories/order", List.of(order(home, foreign, 0)), auth))
                .andExpect(status().isBadRequest());
        // Each category at most once
        mockMvc.perform(putJson("/api/v1/categories/order", List.of(order(home, null, 0), order(home, null, 1)), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("[1].id"));
        mockMvc.perform(putJson("/api/v1/categories/order", List.of(Map.of("id", home)), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Nothing changed
        mockMvc.perform(get("/api/v1/categories").header("Authorization", auth))
                .andExpect(jsonPath("$[?(@.id == " + home + ")].sortOrder", contains(0)))
                .andExpect(jsonPath("$[?(@.id == " + food + ")].children[*].id", contains(cafes.intValue())));
    }

    private static Map<String, Object> order(Long id, Long parentId, int sortOrder) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("parentId", parentId);
        item.put("sortOrder", sortOrder);
        return item;
    }

    @Test
    void createSubcategoryAndListAsTree() throws Exception {
        Long parent = id(json(postJson("/api/v1/categories", category("Travel", "EXPENSE", null), auth), 201));
        Long child = id(json(postJson("/api/v1/categories", category("Hotels", "EXPENSE", parent), auth), 201));

        mockMvc.perform(get("/api/v1/categories").header("Authorization", auth))
                .andExpect(status().isOk())
                // Subcategories are listed only under their parent
                .andExpect(jsonPath("$[?(@.name == 'Hotels')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == " + parent + ")].children[*].id", contains(child.intValue())))
                .andExpect(jsonPath("$[?(@.id == " + parent + ")].children[*].parentId", contains(parent.intValue())));
    }

    @Test
    void subcategoryRules() throws Exception {
        Long expense = id(json(postJson("/api/v1/categories", category("Travel", "EXPENSE", null), auth), 201));
        Long child = id(json(postJson("/api/v1/categories", category("Hotels", "EXPENSE", expense), auth), 201));

        // Only two levels
        mockMvc.perform(postJson("/api/v1/categories", category("Deep", "EXPENSE", child), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("parentId"));
        // The kind must match the parent
        mockMvc.perform(postJson("/api/v1/categories", category("Refunds", "INCOME", expense), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kind"));
        // A category with children cannot be nested
        Long other = id(json(postJson("/api/v1/categories", category("Other", "EXPENSE", null), auth), 201));
        mockMvc.perform(putJson("/api/v1/categories/" + expense, category("Travel", "EXPENSE", other), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("parentId"));
        // A category cannot be its own parent
        mockMvc.perform(putJson("/api/v1/categories/" + other, category("Other", "EXPENSE", other), auth))
                .andExpect(status().isBadRequest());
        // A foreign parent is rejected
        Long foreign =
                id(json(postJson("/api/v1/categories", category("Foreign", "EXPENSE", null), loginNewUser()), 201));
        mockMvc.perform(postJson("/api/v1/categories", category("X", "EXPENSE", foreign), auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changingKindOfParentChangesChildren() throws Exception {
        Long parent = id(json(postJson("/api/v1/categories", category("Side jobs", "EXPENSE", null), auth), 201));
        json(postJson("/api/v1/categories", category("Design", "EXPENSE", parent), auth), 201);

        mockMvc.perform(putJson("/api/v1/categories/" + parent, category("Side jobs", "INCOME", null), auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("INCOME"))
                .andExpect(jsonPath("$.children[0].kind").value("INCOME"));
    }

    @Test
    void categoryWithBudgetCannotBeDeleted() throws Exception {
        Long category = id(json(postJson("/api/v1/categories", category("Gifts", "EXPENSE", null), auth), 201));
        mockMvc.perform(putJson("/api/v1/budget/2026-10/categories/" + category, Map.of("assigned", 100), auth))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/categories/" + category).header("Authorization", auth))
                .andExpect(status().isConflict());
    }

    @Test
    void kindOfUsedCategoryCannotChange() throws Exception {
        Long parent = id(json(postJson("/api/v1/categories", category("Trips", "EXPENSE", null), auth), 201));
        Long child = id(json(postJson("/api/v1/categories", category("Tickets", "EXPENSE", parent), auth), 201));
        mockMvc.perform(putJson("/api/v1/budget/2026-10/categories/" + child, Map.of("assigned", 100), auth))
                .andExpect(status().isOk());

        mockMvc.perform(putJson("/api/v1/categories/" + parent, category("Trips", "INCOME", null), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kind"));
        // Other changes are allowed
        mockMvc.perform(putJson("/api/v1/categories/" + parent, category("Journeys", "EXPENSE", null), auth))
                .andExpect(status().isOk());
    }

    @Test
    void deleteOnlyUnusedCategoriesWithoutChildren() throws Exception {
        Long parent = id(json(postJson("/api/v1/categories", category("Travel", "EXPENSE", null), auth), 201));
        Long child = id(json(postJson("/api/v1/categories", category("Hotels", "EXPENSE", parent), auth), 201));
        Long account = id(json(
                postJson("/api/v1/accounts", Map.of("name", "Cash", "type", "CASH", "currency", "UAH"), auth), 201));
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                "2026-10-01",
                                "accountId",
                                account,
                                "amount",
                                10,
                                "categoryId",
                                child),
                        auth),
                201);
        Long unused = id(json(postJson("/api/v1/categories", category("Unused", "EXPENSE", null), auth), 201));

        mockMvc.perform(delete("/api/v1/categories/" + parent).header("Authorization", auth))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/categories/" + child).header("Authorization", auth))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/categories/" + unused).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/categories").header("Authorization", auth))
                .andExpect(jsonPath("$[*].name", not(hasItem("Unused"))));
    }

    @Test
    void archiveCategory() throws Exception {
        Long id = id(json(postJson("/api/v1/categories", category("Old", "EXPENSE", null), auth), 201));
        Map<String, Object> archived = category("Old", "EXPENSE", null);
        archived.put("archived", true);
        archived.put("sortOrder", 5);

        mockMvc.perform(putJson("/api/v1/categories/" + id, archived, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.sortOrder").value(5));
        mockMvc.perform(delete("/api/v1/categories/999999").header("Authorization", auth))
                .andExpect(status().isNotFound());
    }
}
