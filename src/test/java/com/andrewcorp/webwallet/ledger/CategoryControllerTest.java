package com.andrewcorp.webwallet.ledger;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        mockMvc.perform(postJson("/api/v1/auth/register",
                        Map.of("email", "cat@test.com", "password", PASSWORD, "firstName", "A", "lastName", "B"))
                        .header("Accept-Language", "uk"))
                .andExpect(status().isCreated());
        var user = users.findByEmail("cat@test.com").orElseThrow();
        user.setEmailVerified(true);
        users.save(user);
        String ukAuth = bearer(login("cat@test.com", PASSWORD));

        mockMvc.perform(get("/api/v1/categories").header("Authorization", ukAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Їжа"))
                .andExpect(jsonPath("$[0].kind").value("EXPENSE"))
                .andExpect(jsonPath("$[0].children[0].name").value("Продукти"))
                .andExpect(jsonPath("$[?(@.name == 'Зарплата')].kind", contains("INCOME")));
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
        Long foreign = id(json(postJson("/api/v1/categories", category("Foreign", "EXPENSE", null), loginNewUser()), 201));
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
    void deleteOnlyUnusedCategoriesWithoutChildren() throws Exception {
        Long parent = id(json(postJson("/api/v1/categories", category("Travel", "EXPENSE", null), auth), 201));
        Long child = id(json(postJson("/api/v1/categories", category("Hotels", "EXPENSE", parent), auth), 201));
        Long account = id(json(postJson("/api/v1/accounts", Map.of("name", "Cash", "type", "CASH", "currency", "UAH"), auth), 201));
        json(postJson("/api/v1/transactions", Map.of("type", "EXPENSE", "date", "2026-10-01", "accountId", account,
                "amount", 10, "categoryId", child), auth), 201);
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
