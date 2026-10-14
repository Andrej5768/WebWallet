package com.andrewcorp.webwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountControllerTest extends AbstractIntegrationTest {

    private String auth;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
    }

    private Map<String, Object> account(String name, String type, String currency) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("currency", currency);
        return body;
    }

    @Test
    void createAccountWithDefaults() throws Exception {
        Map<String, Object> card = json(postJson("/api/v1/accounts", account(" Mono ", "CARD", "UAH"), auth), 201);

        assertThat(card)
                .containsEntry("name", "Mono")
                .containsEntry("type", "CARD")
                .containsEntry("currency", "UAH")
                .containsEntry("inBudget", true)
                .containsEntry("archived", false);
        assertThat(((Number) card.get("balance")).doubleValue()).isZero();

        Map<String, Object> deposit =
                json(postJson("/api/v1/accounts", account("Deposit", "DEPOSIT", "USD"), auth), 201);
        assertThat(deposit).containsEntry("inBudget", false);
    }

    @Test
    void createCreditCardWithOpeningBalanceAndLimit() throws Exception {
        Map<String, Object> body = account("Privat", "CREDIT_CARD", "UAH");
        body.put("openingBalance", -47572.53);
        body.put("creditLimit", 70000);
        body.put("interestRate", 3.5);

        mockMvc.perform(postJson("/api/v1/accounts", body, auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(-47572.53))
                .andExpect(jsonPath("$.creditLimit").value(70000))
                .andExpect(jsonPath("$.interestRate").value(3.5));
    }

    @Test
    void createRequiresValidCurrency() throws Exception {
        mockMvc.perform(postJson("/api/v1/accounts", account("Cash", "CASH", "XYZ"), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("currency"));
        mockMvc.perform(postJson("/api/v1/accounts", account("Cash", "CASH", null), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("currency"))
                .andExpect(jsonPath("$.errors[0].message").value("Required"));
    }

    @Test
    void listReturnsOnlyOwnAccountsSorted() throws Exception {
        Map<String, Object> second = account("Second", "CASH", "UAH");
        second.put("sortOrder", 2);
        Map<String, Object> first = account("First", "CASH", "UAH");
        first.put("sortOrder", 1);
        json(postJson("/api/v1/accounts", second, auth), 201);
        json(postJson("/api/v1/accounts", first, auth), 201);
        json(postJson("/api/v1/accounts", account("Foreign", "CASH", "UAH"), loginNewUser()), 201);

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("First"))
                .andExpect(jsonPath("$[1].name").value("Second"));
    }

    @Test
    void updateKeepsCurrency() throws Exception {
        Long id = id(json(postJson("/api/v1/accounts", account("Cash", "CASH", "UAH"), auth), 201));
        Map<String, Object> update = account("Wallet", "CASH", "USD");
        update.put("inBudget", false);
        update.put("archived", true);

        mockMvc.perform(putJson("/api/v1/accounts/" + id, update, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Wallet"))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.inBudget").value(false))
                .andExpect(jsonPath("$.archived").value(true));
        mockMvc.perform(get("/api/v1/accounts/" + id).header("Authorization", auth))
                .andExpect(jsonPath("$.name").value("Wallet"));
    }

    @Test
    void foreignAccountIsNotFound() throws Exception {
        Long foreign = id(json(postJson("/api/v1/accounts", account("Foreign", "CASH", "UAH"), loginNewUser()), 201));

        mockMvc.perform(get("/api/v1/accounts/" + foreign).header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(putJson("/api/v1/accounts/" + foreign, account("x", "CASH", "UAH"), auth))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/accounts/" + foreign).header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteOnlyWithoutTransactions() throws Exception {
        Long empty = id(json(postJson("/api/v1/accounts", account("Empty", "CASH", "UAH"), auth), 201));
        Long used = id(json(postJson("/api/v1/accounts", account("Used", "CASH", "UAH"), auth), 201));
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of("type", "EXPENSE", "date", "2026-10-01", "accountId", used, "amount", 10),
                        auth),
                201);

        mockMvc.perform(delete("/api/v1/accounts/" + empty).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/accounts/" + used).header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_IN_USE"));

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", auth))
                .andExpect(jsonPath("$[*].name", contains("Used")));
    }
}
