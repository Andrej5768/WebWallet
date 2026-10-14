package com.andrewcorp.webwallet.imports.privatbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSource;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.user.User;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The PDF text extraction is replaced by the anonymized text of a statement; it is tested separately.
 */
class PrivatBankImportTest extends AbstractIntegrationTest {

    @MockitoBean
    private PdfText pdfText;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private TransactionTemplate tx;

    private User user;
    private String auth;
    private String sample;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        sample = new ClassPathResource("imports/privat/statement-sample.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                // Git may check the file out with CRLF line endings on Windows
                .replace("\r\n", "\n");
        when(pdfText.extract(any())).thenAnswer(invocation -> sample);
    }

    private MockMultipartHttpServletRequestBuilder upload() {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/imports/privatbank")
                .file(new MockMultipartFile("file", "statement.pdf", "application/pdf", new byte[] {1}));
        request.header("Authorization", auth);
        return request;
    }

    private List<Transaction> userTransactions() {
        return tx.execute(status -> {
            List<Transaction> all = transactions.findAll().stream()
                    .filter(t -> t.getUserId().equals(user.getId()))
                    .toList();
            all.forEach(t -> {
                t.getAccount().getId();
                if (t.getToAccount() != null) {
                    t.getToAccount().getId();
                }
                if (t.getPayee() != null) {
                    t.getPayee().getName();
                }
            });
            return all;
        });
    }

    private static Transaction onDate(List<Transaction> transactions, String date) {
        return transactions.stream()
                .filter(t -> t.getDate().equals(LocalDate.parse(date)))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void importsIntoNewAccount() throws Exception {
        Map<String, Object> result = json(upload(), 200);

        assertThat(result)
                .containsEntry("accountsCreated", 1)
                .containsEntry("transactionsImported", 6)
                .containsEntry("transactionsMatched", 0);
        assertThat((List<?>) result.get("warnings")).isEmpty();

        Account account =
                accounts.findByUserIdOrderBySortOrderAscIdAsc(user.getId()).get(0);
        assertThat(account.getName()).isEqualTo("PrivatBank *1234");
        assertThat(account.getType()).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(account.getCreditLimit()).isEqualByComparingTo("70000");
        assertThat(account.getCardNumbers()).containsExactly("1234");
        // The balance equals the closing balance of the statement
        assertThat(accountService.balances(user.getId()).get(account.getId())).isEqualByComparingTo("-54698.84");

        List<Transaction> imported = userTransactions();
        assertThat(imported).allSatisfy(t -> assertThat(t.getSource()).isEqualTo(TransactionSource.PRIVATBANK));
        Transaction groceries = onDate(imported, "2026-10-11");
        assertThat(groceries.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(groceries.getAmount()).isEqualByComparingTo("3060.51");
        assertThat(groceries.getPayee().getName()).isEqualTo("PRODUKTY SHOP");
        assertThat(groceries.getComment()).isEqualTo("Супермаркети та продукти");
        Transaction steam = onDate(imported, "2026-09-20");
        assertThat(steam.getOriginalAmount()).isEqualByComparingTo("20");
        assertThat(steam.getOriginalCurrency()).isEqualTo("USD");
        assertThat(steam.getComment()).isEqualTo("ID платежу: 123");
        Transaction income = onDate(imported, "2026-08-05");
        assertThat(income.getType()).isEqualTo(TransactionType.INCOME);
        assertThat(income.getPayee().getName()).isEqualTo("IVANENKO PETRO");
        Transaction interest = onDate(imported, "2026-08-01");
        assertThat(interest.getPayee()).isNull();
        assertThat(interest.getComment()).startsWith("Списання відсотків");
    }

    @Test
    void repeatedOrOverlappingStatementAddsNothing() throws Exception {
        json(upload(), 200);
        // A shorter statement with part of the same operations
        sample = sample.replace("Період: 01.08.2026 — 12.10.2026", "Період: 01.09.2026 — 12.10.2026");

        Map<String, Object> second = json(upload(), 200);

        assertThat(second)
                .containsEntry("accountsCreated", 0)
                .containsEntry("transactionsImported", 0)
                .containsEntry("transactionsSkipped", 6);
        assertThat(userTransactions()).hasSize(6);
    }

    @Test
    void usesAccountWithTheCardAndRecognizesKnownOperations() throws Exception {
        Account card = new Account(user.getId(), "Privat from Zen Money", AccountType.CREDIT_CARD, "UAH");
        card.setCardNumbers(List.of("1234"));
        card = accounts.save(card);
        Account mono = accounts.save(new Account(user.getId(), "Monobank", AccountType.CARD, "UAH"));
        // Entered manually, two days after the bank date
        Transaction manual = new Transaction(user.getId());
        manual.setType(TransactionType.EXPENSE);
        manual.setDate(LocalDate.of(2026, 10, 13));
        manual.setAccount(card);
        manual.setAmount(new BigDecimal("3060.51"));
        Long manualId = transactions.save(manual).getId();
        // The other half of a Privat → Monobank transfer, 3% fee
        Transaction received = new Transaction(user.getId());
        received.setSource(TransactionSource.MONOBANK);
        received.setType(TransactionType.INCOME);
        received.setDate(LocalDate.of(2026, 10, 5));
        received.setAccount(mono);
        received.setAmount(new BigDecimal("10000"));
        Long receivedId = transactions.save(received).getId();

        Map<String, Object> result = json(upload(), 200);

        assertThat(result)
                .containsEntry("accountsCreated", 0)
                .containsEntry("transactionsImported", 4)
                .containsEntry("transactionsMatched", 2);
        List<Transaction> all = userTransactions();
        assertThat(all).hasSize(6);
        assertThat(all)
                .filteredOn(t -> t.getAccount().getId().equals(mono.getId())
                        || mono.getId()
                                .equals(
                                        t.getToAccount() == null
                                                ? null
                                                : t.getToAccount().getId()))
                .singleElement()
                .satisfies(transfer -> {
                    assertThat(transfer.getId()).isEqualTo(receivedId);
                    assertThat(transfer.getType()).isEqualTo(TransactionType.TRANSFER);
                    assertThat(transfer.getAmount()).isEqualByComparingTo("10300");
                    assertThat(transfer.getToAmount()).isEqualByComparingTo("10000");
                });
        assertThat(transactions.findById(manualId).orElseThrow().getBankReference())
                .isNotNull();

        // Imported again: the matched operations are recognized as well
        Map<String, Object> second = json(upload(), 200);
        assertThat(second).containsEntry("transactionsImported", 0).containsEntry("transactionsSkipped", 6);
        // An existing account keeps its own opening balance
        assertThat(accounts.findById(card.getId()).orElseThrow().getOpeningBalance())
                .isEqualByComparingTo("0");
    }

    @Test
    void importIntoChosenAccount() throws Exception {
        Account usd = accounts.save(new Account(user.getId(), "Dollars", AccountType.CARD, "USD"));
        Account other = accounts.save(new Account(user.getId(), "Other card", AccountType.CARD, "UAH"));

        mockMvc.perform(upload().param("accountId", usd.getId().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("accountId"));
        mockMvc.perform(upload().param("accountId", "999999")).andExpect(status().isBadRequest());

        json(upload().param("accountId", other.getId().toString()), 200);

        assertThat(userTransactions())
                .hasSize(6)
                .allSatisfy(t -> assertThat(t.getAccount().getId()).isEqualTo(other.getId()));
    }

    @Test
    void parserWarningsAreReturned() throws Exception {
        sample = sample.replace("UAH -10 300,00 0,00 0,00 -51 638,33", "UAH -10 300,00 0,00 0,00 -51 000,00");

        Map<String, Object> result = json(upload(), 200);

        assertThat((List<?>) result.get("warnings")).isNotEmpty();
    }

    @Test
    void bankCategoryRules() throws Exception {
        Long groceries =
                id(json(postJson("/api/v1/categories", Map.of("name", "Groceries", "kind", "EXPENSE"), auth), 201));
        json(
                postJson(
                        "/api/v1/categorization-rules",
                        Map.of("categoryId", groceries, "bankCategory", "Супермаркети та продукти"),
                        auth),
                201);

        json(upload(), 200);

        Transaction imported = tx.execute(status -> {
            Transaction t = onDate(
                    transactions.findAll().stream()
                            .filter(candidate -> candidate.getUserId().equals(user.getId()))
                            .toList(),
                    "2026-10-11");
            t.getSplits().get(0).getCategory().getId();
            return t;
        });
        assertThat(imported.getBankCategory()).isEqualTo("Супермаркети та продукти");
        assertThat(imported.getSplits().get(0).getCategory().getId()).isEqualTo(groceries);
    }

    @Test
    void otherDocumentIsRejected() throws Exception {
        sample = "Кредитний договір";

        mockMvc.perform(upload())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT_FILE"));
    }
}
