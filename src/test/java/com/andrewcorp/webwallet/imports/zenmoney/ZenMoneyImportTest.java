package com.andrewcorp.webwallet.imports.zenmoney;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ZenMoneyImportTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private ExchangeRateRepository rates;

    @MockitoBean
    private ZenMoneyClient client;

    private User user;
    private String auth;
    private byte[] dump;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        dump = new ClassPathResource("imports/zenmoney/dump.json").getContentAsByteArray();
    }

    private Map<String, Object> importFile() throws Exception {
        return json(multipart("/api/v1/imports/zenmoney/file")
                .file(new MockMultipartFile("file", "dump.json", "application/json", dump))
                .header("Authorization", auth), 200);
    }

    private Account account(String externalId) {
        return accounts.findByUserIdAndExternalSourceAndExternalId(user.getId(), "ZENMONEY", externalId).orElseThrow();
    }

    @Test
    void importsAccountsCategoriesPayeesAndTransactions() throws Exception {
        // The budget converts the dollar account at the NBU rate
        rates.save(new ExchangeRate("USD", LocalDate.of(2026, 9, 1), new BigDecimal("41.5")));
        Map<String, Object> result = importFile();

        assertThat(result).containsEntry("accountsCreated", 7)
                .containsEntry("categoriesCreated", 5)
                .containsEntry("payeesCreated", 4)
                .containsEntry("transactionsImported", 8)
                .containsEntry("transactionsSkipped", 1)
                .containsEntry("budgetsImported", 1);
        assertThat((List<?>) result.get("warnings")).hasSize(3);

        // Accounts
        Account card = account("acc-card");
        assertThat(card.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(card.getCreditLimit()).isEqualByComparingTo("70000");
        assertThat(card.getInterestRate()).isEqualByComparingTo("3.5");
        assertThat(card.getCardNumbers()).containsExactly("1234", "9876");
        assertThat(card.isInBudget()).isTrue();
        assertThat(account("acc-debt").getType()).isEqualTo(AccountType.DEBT);
        assertThat(account("acc-deposit").isInBudget()).isFalse();
        assertThat(account("acc-old").isArchived()).isTrue();
        assertThat(account("acc-jar").getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(account("acc-usd").getCurrency()).isEqualTo("USD");

        // Balances are the same as in Zen Money
        mockMvc.perform(get("/api/v1/accounts/" + card.getId()).header("Authorization", auth))
                .andExpect(jsonPath("$.balance").value(-1000));
        mockMvc.perform(get("/api/v1/accounts/" + account("acc-cash").getId()).header("Authorization", auth))
                .andExpect(jsonPath("$.balance").value(500));
        mockMvc.perform(get("/api/v1/accounts/" + account("acc-usd").getId()).header("Authorization", auth))
                .andExpect(jsonPath("$.balance").value(100));
        mockMvc.perform(get("/api/v1/accounts/" + account("acc-debt").getId()).header("Authorization", auth))
                .andExpect(jsonPath("$.balance").value(200));

        // Categories: hierarchy and a twin for a tag used for both expenses and income
        Category food = category("tag-food");
        Category groceries = category("tag-groceries");
        assertThat(groceries.getParentId()).isEqualTo(food.getId());
        assertThat(category("tag-salary").getKind()).isEqualTo(CategoryKind.INCOME);
        assertThat(category("tag-gifts").getKind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(category("tag-gifts:INCOME").getKind()).isEqualTo(CategoryKind.INCOME);
        assertThat(category("tag-gifts:INCOME").getName()).isEqualTo("Gifts");

        // The monthly budget of a category becomes an envelope assignment
        Map<String, Object> september = json(get("/api/v1/budget/2026-09").header("Authorization", auth), 200);
        assertThat(september.get("assigned").toString()).isEqualTo("3000.0");

        // Base currency comes from the Zen Money user
        assertThat(users.findById(user.getId()).orElseThrow().getBaseCurrency()).isEqualTo("UAH");
    }

    private Category category(String externalId) {
        return categories.findByUserIdAndExternalSourceAndExternalId(user.getId(), "ZENMONEY", externalId).orElseThrow();
    }

    @Test
    void transactionShapes() throws Exception {
        importFile();

        mockMvc.perform(get("/api/v1/transactions?size=50").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(8))
                // Expense with merchant and bank id
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-01')].type", contains("EXPENSE")))
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-01')].payee.name", contains("Silpo")))
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-01')].comment", contains("weekly")))
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-01')].source", contains("ZENMONEY")))
                // Transfer with a fee
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-03')].amount", contains(1030.0)))
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-03')].toAmount", contains(1000.0)))
                // Cross-currency transfer
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-04')].toCurrency", contains("USD")))
                // Foreign currency purchase
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-05')].originalAmount", contains(20.0)))
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-05')].originalCurrency", contains("USD")))
                // Lending money to a person is a transfer to the debt account
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-07')].type", contains("TRANSFER")))
                .andExpect(jsonPath("$.items[?(@.date == '2026-09-07')].payee.name", contains("Ivan")));
    }

    @Test
    void repeatedImportAddsNothing() throws Exception {
        importFile();

        Map<String, Object> second = importFile();

        assertThat(second).containsEntry("accountsCreated", 0)
                .containsEntry("accountsUpdated", 7)
                .containsEntry("categoriesCreated", 0)
                .containsEntry("payeesCreated", 0)
                .containsEntry("transactionsImported", 0)
                .containsEntry("transactionsSkipped", 9);
        mockMvc.perform(get("/api/v1/transactions").header("Authorization", auth))
                .andExpect(jsonPath("$.totalItems").value(8));
        mockMvc.perform(get("/api/v1/accounts/" + account("acc-card").getId()).header("Authorization", auth))
                .andExpect(jsonPath("$.balance").value(-1000));
    }

    @Test
    void importWithToken() throws Exception {
        when(client.fetchAll("zen-token")).thenReturn(jsonMapper.readValue(dump, ZenMoneyDump.class));

        mockMvc.perform(postJson("/api/v1/imports/zenmoney", Map.of("token", "zen-token"), auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsImported").value(8));
    }

    @Test
    void rejectedTokenIsReported() throws Exception {
        when(client.fetchAll(anyString())).thenThrow(new ApiException(ErrorCode.INVALID_EXTERNAL_TOKEN));

        mockMvc.perform(postJson("/api/v1/imports/zenmoney", Map.of("token", "bad"), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXTERNAL_TOKEN"));
    }

    @Test
    void invalidFileIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/v1/imports/zenmoney/file")
                        .file(new MockMultipartFile("file", "dump.json", "application/json", "not json".getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT_FILE"));
        mockMvc.perform(multipart("/api/v1/imports/zenmoney/file")
                        .file(new MockMultipartFile("file", "dump.json", "application/json", "{}".getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT_FILE"));
    }

    @Test
    void importRequiresAuthentication() throws Exception {
        mockMvc.perform(postJson("/api/v1/imports/zenmoney", Map.of("token", "x")))
                .andExpect(status().isUnauthorized());
    }
}
