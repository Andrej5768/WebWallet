package com.andrewcorp.webwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountControllerTest extends AbstractIntegrationTest {

    @Autowired
    private ExchangeRateRepository rates;

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
    void omittedFieldsKeepTheirValuesOnUpdate() throws Exception {
        Map<String, Object> body = account("Card", "CREDIT_CARD", "UAH");
        body.put("openingBalance", 1500);
        body.put("creditLimit", 20000);
        body.put("archived", true);
        Long id = id(json(postJson("/api/v1/accounts", body, auth), 201));

        mockMvc.perform(putJson("/api/v1/accounts/" + id, Map.of("name", "Renamed", "type", "CREDIT_CARD"), auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.openingBalance").value(1500))
                .andExpect(jsonPath("$.creditLimit").value(20000))
                .andExpect(jsonPath("$.archived").value(true));
        // 0 removes the credit limit
        mockMvc.perform(putJson(
                        "/api/v1/accounts/" + id, Map.of("name", "Renamed", "type", "CARD", "creditLimit", 0), auth))
                .andExpect(jsonPath("$.creditLimit").doesNotExist());
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
    void countsTheOperationsOfEveryAccount() throws Exception {
        Long card = createAccount(auth, "Card", "CARD", "UAH", 100);
        Long cash = createAccount(auth, "Cash", "CASH", "UAH", 0);
        Long unused = createAccount(auth, "Unused", "CASH", "UAH", 0);
        Map<String, Object> expense = Map.of("type", "EXPENSE", "date", "2026-10-01", "accountId", card, "amount", 1);
        json(postJson("/api/v1/transactions", expense, auth), 201);
        json(postJson("/api/v1/transactions", expense, auth), 201);
        Map<String, Object> transfer =
                Map.of("type", "TRANSFER", "date", "2026-10-02", "accountId", card, "toAccountId", cash, "amount", 10);
        json(postJson("/api/v1/transactions", transfer, auth), 201);

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operations").value(3))
                .andExpect(jsonPath("$[1].operations").value(1))
                .andExpect(jsonPath("$[2].operations").value(0));
        mockMvc.perform(get("/api/v1/accounts/" + cash).header("Authorization", auth))
                .andExpect(jsonPath("$.operations").value(1));
        assertThat(unused).isNotNull();
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
    void balancesInBaseCurrencyAndSummary() throws Exception {
        LocalDate today = LocalDate.now(Zones.KYIV);
        rates.save(new ExchangeRate("USD", today, new BigDecimal("40")));
        Long card = id(json(postJson("/api/v1/accounts", account("Card", "CARD", "UAH", 1000), auth), 201));
        Long dollars = id(json(postJson("/api/v1/accounts", account("Dollars", "CASH", "USD", 100), auth), 201));
        Long euros = id(json(postJson("/api/v1/accounts", account("Euros", "CASH", "EUR", 50), auth), 201));
        Map<String, Object> credit = account("Credit", "CREDIT_CARD", "UAH", -500);
        credit.put("creditLimit", 2000);
        Long creditCard = id(json(postJson("/api/v1/accounts", credit, auth), 201));
        json(postJson("/api/v1/accounts", account("Deposit", "DEPOSIT", "UAH", 10000), auth), 201);
        Map<String, Object> archived = account("Old", "CASH", "UAH", 777);
        archived.put("archived", true);
        json(postJson("/api/v1/accounts", archived, auth), 201);

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + card + ")].balanceInBase", contains(1000.0)))
                .andExpect(jsonPath("$[?(@.id == " + dollars + ")].balanceInBase", contains(4000.0)))
                // No rate, no converted balance
                .andExpect(jsonPath("$[?(@.id == " + euros + ")].balanceInBase", contains((Object) null)))
                .andExpect(jsonPath("$[?(@.id == " + creditCard + ")].availableCredit", contains(1500.0)))
                .andExpect(jsonPath("$[?(@.id == " + card + ")].availableCredit", contains((Object) null)));

        Map<String, Object> budget =
                json(get("/api/v1/budget/" + YearMonth.from(today)).header("Authorization", auth), 200);
        mockMvc.perform(get("/api/v1/accounts/summary").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("UAH"))
                // 1000 + 4000 - 500 + 10000; the euros are left out, the archived account is not counted
                .andExpect(jsonPath("$.netWorth").value(14500.0))
                .andExpect(jsonPath("$.available").value(5000.0))
                .andExpect(jsonPath("$.availableWithCredit").value(6500.0))
                .andExpect(jsonPath("$.readyToAssign").value(budget.get("readyToAssign")))
                .andExpect(jsonPath("$.unconvertedCurrencies", contains("EUR")));
    }

    private Map<String, Object> account(String name, String type, String currency, double openingBalance) {
        Map<String, Object> body = account(name, type, currency);
        body.put("openingBalance", openingBalance);
        return body;
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
