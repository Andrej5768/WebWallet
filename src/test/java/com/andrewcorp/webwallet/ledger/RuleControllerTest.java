package com.andrewcorp.webwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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

class RuleControllerTest extends AbstractIntegrationTest {

    private String auth;
    private Long account;
    private Long food;
    private Long taxi;
    private Long salary;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        account = id(json(
                postJson("/api/v1/accounts", Map.of("name", "Card", "type", "CARD", "currency", "UAH"), auth), 201));
        food = category("Food", "EXPENSE");
        taxi = category("Taxi", "EXPENSE");
        salary = category("Salary", "INCOME");
    }

    private Long category(String name, String kind) throws Exception {
        return id(json(postJson("/api/v1/categories", Map.of("name", name, "kind", kind), auth), 201));
    }

    private Long expense(String payee, Long categoryId) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "type", "EXPENSE", "date", "2026-10-01", "accountId", account, "amount", 100, "payeeName", payee));
        body.put("categoryId", categoryId);
        return id(json(postJson("/api/v1/transactions", body, auth), 201));
    }

    @SuppressWarnings("unchecked")
    private Long categoryOf(Long transactionId) throws Exception {
        Map<String, Object> transaction =
                json(get("/api/v1/transactions/" + transactionId).header("Authorization", auth), 200);
        Object id =
                ((List<Map<String, Object>>) transaction.get("splits")).get(0).get("categoryId");
        return id == null ? null : ((Number) id).longValue();
    }

    @Test
    void crudAndValidation() throws Exception {
        Long id = id(json(
                postJson(
                        "/api/v1/categorization-rules",
                        Map.of("categoryId", taxi, "payeePattern", " uber ", "mcc", 4121),
                        auth),
                201));

        mockMvc.perform(get("/api/v1/categorization-rules").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payeePattern").value("uber"))
                .andExpect(jsonPath("$[0].mcc").value(4121))
                .andExpect(jsonPath("$[0].learned").value(false));

        mockMvc.perform(putJson(
                        "/api/v1/categorization-rules/" + id,
                        Map.of("categoryId", food, "bankCategory", "Таксі"),
                        auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(food))
                .andExpect(jsonPath("$.payeePattern").doesNotExist())
                .andExpect(jsonPath("$.bankCategory").value("Таксі"));

        // A rule needs a condition and own references
        mockMvc.perform(postJson("/api/v1/categorization-rules", Map.of("categoryId", food), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("A rule needs at least one condition"));
        mockMvc.perform(postJson("/api/v1/categorization-rules", Map.of("categoryId", 999999, "mcc", 1), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
        mockMvc.perform(postJson("/api/v1/categorization-rules", Map.of("categoryId", food, "payeeId", 999999), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payeeId"));
        mockMvc.perform(putJson(
                        "/api/v1/categorization-rules/" + id, Map.of("categoryId", food, "mcc", 1), loginNewUser()))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/categorization-rules/" + id).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/categorization-rules").header("Authorization", auth))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void manualCategoryChangeIsLearned() throws Exception {
        Long id = expense("Uklon", null);

        mockMvc.perform(putJson(
                        "/api/v1/transactions/" + id,
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                "2026-10-01",
                                "accountId",
                                account,
                                "amount",
                                100,
                                "payeeName",
                                "Uklon",
                                "categoryId",
                                food),
                        auth))
                .andExpect(status().isOk());
        mockMvc.perform(putJson(
                        "/api/v1/transactions/" + id,
                        Map.of(
                                "type",
                                "EXPENSE",
                                "date",
                                "2026-10-01",
                                "accountId",
                                account,
                                "amount",
                                100,
                                "payeeName",
                                "Uklon",
                                "categoryId",
                                taxi),
                        auth))
                .andExpect(status().isOk());

        // One learned rule per payee, with the latest category
        mockMvc.perform(get("/api/v1/categorization-rules").header("Authorization", auth))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].learned").value(true))
                .andExpect(jsonPath("$[0].payee.name").value("Uklon"))
                .andExpect(jsonPath("$[0].categoryId").value(taxi));
    }

    @Test
    void applyCategorizesOnlyUncategorizedTransactions() throws Exception {
        Long uber = expense("UBER *TRIP", null);
        Long boltWithCategory = expense("Uber Eats", food);
        Long other = expense("Silpo", null);
        Long transfer = id(json(
                postJson("/api/v1/accounts", Map.of("name", "Cash", "type", "CASH", "currency", "UAH"), auth), 201));
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2026-10-01",
                                "accountId",
                                account,
                                "amount",
                                5,
                                "toAccountId",
                                transfer),
                        auth),
                201);
        // Income rules do not touch expenses
        json(postJson("/api/v1/categorization-rules", Map.of("categoryId", salary, "payeePattern", "uber"), auth), 201);
        json(postJson("/api/v1/categorization-rules", Map.of("categoryId", taxi, "payeePattern", "uber"), auth), 201);

        Map<String, Object> result = json(postJson("/api/v1/categorization-rules/apply", Map.of(), auth), 200);

        assertThat(result).containsEntry("categorized", 1);
        assertThat(categoryOf(uber)).isEqualTo(taxi);
        assertThat(categoryOf(boltWithCategory)).isEqualTo(food);
        assertThat(categoryOf(other)).isNull();
    }

    @Test
    void applyWithoutRules() throws Exception {
        expense("Silpo", null);

        assertThat(json(postJson("/api/v1/categorization-rules/apply", Map.of(), auth), 200))
                .containsEntry("categorized", 0);
    }
}
