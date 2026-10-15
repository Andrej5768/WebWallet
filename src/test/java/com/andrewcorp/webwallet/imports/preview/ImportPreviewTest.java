package com.andrewcorp.webwallet.imports.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.imports.privatbank.PdfText;
import com.andrewcorp.webwallet.imports.zenmoney.ZenMoneyClient;
import com.andrewcorp.webwallet.imports.zenmoney.ZenMoneyDump;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.PayeeRepository;
import com.andrewcorp.webwallet.ledger.rule.CategorizationRuleRepository;
import com.andrewcorp.webwallet.ledger.tag.Tag;
import com.andrewcorp.webwallet.ledger.tag.TagRepository;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.user.User;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Previews of PrivatBank statements (the PDF text extraction is replaced by the anonymized text of a statement) and
 * Zen Money exports, and their confirmation.
 */
class ImportPreviewTest extends AbstractIntegrationTest {

    @MockitoBean
    private PdfText pdfText;

    @MockitoBean
    private ZenMoneyClient zenMoneyClient;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private TagRepository tags;

    @Autowired
    private PayeeRepository payees;

    @Autowired
    private CategorizationRuleRepository rules;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private ImportPreviewCleanupTask cleanupTask;

    @Autowired
    private ImportPreviewRepository previews;

    @Autowired
    private TransactionTemplate tx;

    private User user;
    private String auth;
    private byte[] zenDump;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        String statement = new ClassPathResource("imports/privat/statement-sample.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        when(pdfText.extract(any())).thenReturn(statement);
        zenDump = new ClassPathResource("imports/zenmoney/dump.json").getContentAsByteArray();
    }

    private Map<String, Object> previewPrivat() throws Exception {
        return json(
                multipart("/api/v1/imports/privatbank/preview")
                        .file(new MockMultipartFile("file", "statement.pdf", "application/pdf", new byte[] {1}))
                        .header("Authorization", auth),
                200);
    }

    private Map<String, Object> previewZenFile() throws Exception {
        return json(
                multipart("/api/v1/imports/zenmoney/file/preview")
                        .file(new MockMultipartFile("file", "dump.json", "application/json", zenDump))
                        .header("Authorization", auth),
                200);
    }

    private Map<String, Object> confirm(Map<String, Object> preview, List<Map<String, Object>> edits) throws Exception {
        return json(
                postJson(
                        "/api/v1/imports/previews/" + preview.get("id") + "/confirm",
                        Map.of("operations", edits),
                        auth),
                200);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operations(Map<String, Object> preview) {
        return (List<Map<String, Object>>) ((Map<String, Object>) preview.get("operations")).get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> preview) {
        return (Map<String, Object>) preview.get("summary");
    }

    private static Map<String, Object> onDate(Map<String, Object> preview, String date) {
        return operations(preview).stream()
                .filter(operation -> operation.get("date").equals(date))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> json, String field) {
        return (Map<String, Object>) json.get(field);
    }

    private static BigDecimal amount(Object json) {
        return new BigDecimal(json.toString());
    }

    private static Map<String, Object> edit(Map<String, Object> operation, Object... fields) {
        Map<String, Object> edit = new HashMap<>(Map.of("key", operation.get("key")));
        for (int i = 0; i < fields.length; i += 2) {
            edit.put((String) fields[i], fields[i + 1]);
        }
        return edit;
    }

    private List<Transaction> userTransactions() {
        return tx.execute(status -> {
            List<Transaction> all = transactions.findAll().stream()
                    .filter(t -> t.getUserId().equals(user.getId()))
                    .toList();
            all.forEach(t -> {
                t.getAccount().getId();
                if (t.getToAccount() != null) {
                    t.getToAccount().getName();
                }
                t.getTags().size();
                t.getSplits().forEach(split -> {
                    if (split.getCategory() != null) {
                        split.getCategory().getId();
                    }
                });
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

    // PrivatBank

    @Test
    void privatPreviewSavesNothing() throws Exception {
        Map<String, Object> preview = previewPrivat();

        assertThat(preview).containsEntry("source", "PRIVATBANK").containsKeys("id", "expiresAt");
        assertThat(summary(preview)).containsEntry("accountsCreated", 1).containsEntry("transactionsImported", 6);
        assertThat(operations(preview)).hasSize(6).allSatisfy(operation -> {
            assertThat(operation).containsEntry("duplicate", false).containsEntry("currency", "UAH");
            assertThat(object(operation, "account"))
                    .containsEntry("id", null)
                    .containsEntry("name", "PrivatBank *1234")
                    .containsEntry("created", true);
        });
        // Newest first
        Map<String, Object> groceries = operations(preview).get(0);
        assertThat(groceries)
                .containsEntry("date", "2026-10-11")
                .containsEntry("payee", "PRODUKTY SHOP")
                .containsEntry("type", "EXPENSE")
                .containsEntry("comment", "Супермаркети та продукти")
                .containsEntry("tagIds", List.of());
        assertThat(amount(groceries.get("amount"))).isEqualByComparingTo("3060.51");
        assertThat(onDate(preview, "2026-08-05")).containsEntry("type", "INCOME");

        // Nothing was saved
        assertThat(accounts.findByUserIdOrderBySortOrderAscIdAsc(user.getId())).isEmpty();
        assertThat(userTransactions()).isEmpty();
        assertThat(payees.findByUserIdOrderByNameAsc(user.getId())).isEmpty();
    }

    @Test
    void confirmImportsTheReviewedOperations() throws Exception {
        Category groceries = categories.save(new Category(user.getId(), null, "Groceries", CategoryKind.EXPENSE));
        Category interest = categories.save(new Category(user.getId(), null, "Interest", CategoryKind.EXPENSE));
        Tag weekly = tags.save(new Tag(user.getId(), "weekly"));
        Map<String, Object> preview = previewPrivat();
        assertThat(onDate(preview, "2026-10-11")).containsEntry("categoryId", null);

        Map<String, Object> result = confirm(
                preview,
                List.of(
                        edit(
                                onDate(preview, "2026-10-11"),
                                "categoryId",
                                groceries.getId(),
                                "tagIds",
                                List.of(weekly.getId()),
                                "comment",
                                " Food for the week ",
                                "rememberRule",
                                true),
                        edit(onDate(preview, "2026-08-01"), "include", false),
                        edit(onDate(preview, "2026-09-20"), "categoryId", interest.getId())));

        assertThat(result)
                .containsEntry("accountsCreated", 1)
                .containsEntry("transactionsImported", 5)
                .containsEntry("transactionsSkipped", 1);
        List<Transaction> imported = userTransactions();
        assertThat(imported).hasSize(5).noneMatch(t -> t.getDate().equals(LocalDate.of(2026, 8, 1)));
        Transaction food = onDate(imported, "2026-10-11");
        assertThat(food.getSplits().get(0).getCategory().getId()).isEqualTo(groceries.getId());
        assertThat(food.getTags()).extracting(Tag::getId).containsExactly(weekly.getId());
        assertThat(food.getComment()).isEqualTo("Food for the week");
        // A changed operation keeps only what was sent: no comment
        assertThat(onDate(imported, "2026-09-20").getComment()).isNull();
        // The rule is remembered for the payee
        assertThat(rules.findByUserIdOrderByIdAsc(user.getId())).singleElement().satisfies(rule -> {
            assertThat(rule.isLearned()).isTrue();
            String payee = tx.execute(status ->
                    rules.findById(rule.getId()).orElseThrow().getPayee().getName());
            assertThat(payee).isEqualTo("PRODUKTY SHOP");
        });

        // The preview is gone
        mockMvc.perform(postJson("/api/v1/imports/previews/" + preview.get("id") + "/confirm", Map.of(), auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void operationsImportedBeforeAreDuplicates() throws Exception {
        json(
                multipart("/api/v1/imports/privatbank")
                        .file(new MockMultipartFile("file", "statement.pdf", "application/pdf", new byte[] {1}))
                        .header("Authorization", auth),
                200);
        Long accountId = accounts.findByUserIdOrderBySortOrderAscIdAsc(user.getId())
                .get(0)
                .getId();

        Map<String, Object> preview = previewPrivat();

        assertThat(summary(preview))
                .containsEntry("accountsCreated", 0)
                .containsEntry("transactionsImported", 0)
                .containsEntry("transactionsSkipped", 6);
        assertThat(operations(preview)).hasSize(6).allSatisfy(operation -> {
            assertThat(operation).containsEntry("duplicate", true);
            assertThat(object(operation, "account"))
                    .containsEntry("id", accountId.intValue())
                    .containsEntry("created", false);
        });
        assertThat(onDate(preview, "2026-10-11")).containsEntry("payee", "PRODUKTY SHOP");
    }

    @Test
    void typeChanges() throws Exception {
        Account cash = accounts.save(new Account(user.getId(), "Cash", AccountType.CASH, "UAH"));
        Account dollars = accounts.save(new Account(user.getId(), "Dollars", AccountType.CASH, "USD"));
        Category salary = categories.save(new Category(user.getId(), null, "Salary", CategoryKind.INCOME));
        Map<String, Object> preview = previewPrivat();
        Map<String, Object> transfer = onDate(preview, "2026-10-05");
        Map<String, Object> income = onDate(preview, "2026-08-05");
        String url = "/api/v1/imports/previews/" + preview.get("id") + "/confirm";

        // Money leaving the account cannot be an income
        mockMvc.perform(postJson(url, Map.of("operations", List.of(edit(transfer, "type", "INCOME"))), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("operations[0].type"));
        mockMvc.perform(postJson(url, Map.of("operations", List.of(edit(transfer, "type", "TRANSFER"))), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("operations[0].transferAccountId"));
        mockMvc.perform(postJson(
                        url,
                        Map.of(
                                "operations",
                                List.of(edit(transfer, "type", "TRANSFER", "transferAccountId", dollars.getId()))),
                        auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("operations[0].transferAccountId"));
        mockMvc.perform(postJson(
                        url, Map.of("operations", List.of(edit(transfer, "categoryId", salary.getId()))), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("operations[0].categoryId"));
        mockMvc.perform(postJson(url, Map.of("operations", List.of(Map.of("key", "unknown"))), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("operations[0].key"));

        confirm(
                preview,
                List.of(
                        edit(transfer, "type", "TRANSFER", "transferAccountId", cash.getId()),
                        edit(income, "type", "TRANSFER", "transferAccountId", cash.getId())));

        List<Transaction> imported = userTransactions();
        Transaction out = onDate(imported, "2026-10-05");
        assertThat(out.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(out.getToAccount().getId()).isEqualTo(cash.getId());
        assertThat(out.getToAmount()).isEqualByComparingTo("10300");
        assertThat(out.getSplits()).isEmpty();
        Transaction in = onDate(imported, "2026-08-05");
        assertThat(in.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(in.getAccount().getId()).isEqualTo(cash.getId());
        assertThat(in.getToAccount().getName()).isEqualTo("PrivatBank *1234");
    }

    // Zen Money

    @Test
    void zenMoneyPreviewSummarizesAndPages() throws Exception {
        Map<String, Object> preview = previewZenFile();

        assertThat(preview).containsEntry("source", "ZENMONEY");
        assertThat(summary(preview))
                .containsEntry("accountsCreated", 7)
                .containsEntry("categoriesCreated", 5)
                .containsEntry("transactionsImported", 8)
                .containsEntry("budgetsImported", 1);
        assertThat(object(preview, "operations"))
                .containsEntry("totalItems", 8)
                .containsEntry("size", ImportPreviewService.DEFAULT_PAGE_SIZE);
        // A category the import creates has a name but no id yet
        assertThat(onDate(preview, "2026-09-01"))
                .containsEntry("key", "tx-groceries")
                .containsEntry("payee", "Silpo")
                .containsEntry("categoryId", null)
                .containsEntry("categoryName", "Groceries");
        Map<String, Object> transfer = onDate(preview, "2026-09-03");
        assertThat(transfer).containsEntry("type", "TRANSFER");
        assertThat(amount(transfer.get("amount"))).isEqualByComparingTo("1030");
        assertThat(amount(transfer.get("toAmount"))).isEqualByComparingTo("1000");
        assertThat(object(transfer, "toAccount"))
                .containsEntry("id", null)
                .containsEntry("name", "Cash")
                .containsEntry("currency", "UAH")
                .containsEntry("created", true);
        assertThat(accounts.findByUserIdOrderBySortOrderAscIdAsc(user.getId())).isEmpty();

        Map<String, Object> page = json(
                get("/api/v1/imports/previews/" + preview.get("id") + "?page=2&size=3")
                        .header("Authorization", auth),
                200);
        assertThat(operations(page)).hasSize(2);
        assertThat(object(page, "operations")).containsEntry("totalPages", 3);
        mockMvc.perform(get("/api/v1/imports/previews/" + preview.get("id") + "?size=501")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());

        // A transfer may become an expense of its source account
        Map<String, Object> result = confirm(
                preview,
                List.of(edit(transfer, "type", "EXPENSE"), edit(onDate(preview, "2026-09-02"), "include", false)));

        assertThat(result).containsEntry("accountsCreated", 7).containsEntry("transactionsImported", 7);
        Transaction expense = onDate(userTransactions(), "2026-09-03");
        assertThat(expense.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(expense.getAmount()).isEqualByComparingTo("1030");
        assertThat(expense.getToAccount()).isNull();

        // Previewed again, the imported transactions are duplicates; the left out one is not
        Map<String, Object> again = previewZenFile();
        assertThat(operations(again)).hasSize(8);
        assertThat(operations(again))
                .filteredOn(o -> o.get("duplicate").equals(true))
                .hasSize(7);
        assertThat(onDate(again, "2026-09-02")).containsEntry("duplicate", false);
        assertThat(onDate(again, "2026-09-01")).containsEntry("payee", "Silpo");
    }

    @Test
    void zenMoneyPreviewWithToken() throws Exception {
        when(zenMoneyClient.fetchAll("zen-token")).thenReturn(jsonMapper.readValue(zenDump, ZenMoneyDump.class));

        Map<String, Object> preview =
                json(postJson("/api/v1/imports/zenmoney/preview", Map.of("token", "zen-token"), auth), 200);

        assertThat(summary(preview)).containsEntry("transactionsImported", 8);
        assertThat(previews.findAll())
                .singleElement()
                .satisfies(stored -> assertThat(stored.getInput()).doesNotContain("zen-token"));
    }

    // lifetime

    @Test
    void previewsExpireAndBelongToTheirUser() throws Exception {
        Map<String, Object> preview = previewPrivat();
        String url = "/api/v1/imports/previews/" + preview.get("id");

        mockMvc.perform(get(url).header("Authorization", loginNewUser())).andExpect(status().isNotFound());
        mockMvc.perform(get(url).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operations.totalItems").value(6));

        TestClock.INSTANCE.set(Instant.now().plus(Duration.ofMinutes(61)));
        mockMvc.perform(get(url).header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        cleanupTask.purge();
        assertThat(previews.count()).isZero();
    }

    @Test
    void discardAndLimitPerUser() throws Exception {
        Map<String, Object> first = previewPrivat();
        for (int i = 0; i < ImportPreviewService.MAX_PREVIEWS_PER_USER; i++) {
            previewPrivat();
        }
        // The oldest one is removed
        assertThat(previews.count()).isEqualTo(ImportPreviewService.MAX_PREVIEWS_PER_USER);
        mockMvc.perform(get("/api/v1/imports/previews/" + first.get("id")).header("Authorization", auth))
                .andExpect(status().isNotFound());

        Map<String, Object> last = previewPrivat();
        mockMvc.perform(delete("/api/v1/imports/previews/" + last.get("id")).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/imports/previews/" + last.get("id")).header("Authorization", auth))
                .andExpect(status().isNotFound());
    }
}
