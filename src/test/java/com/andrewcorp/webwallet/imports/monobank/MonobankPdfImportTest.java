package com.andrewcorp.webwallet.imports.monobank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.imports.privatbank.PdfText;
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
import org.springframework.transaction.support.TransactionTemplate;

/** The PDF text extraction is replaced by the synthetic text of a statement; it is tested separately. */
class MonobankPdfImportTest extends AbstractIntegrationTest {

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
        sample = new ClassPathResource("imports/monobank/statement-sample.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        when(pdfText.extract(any())).thenAnswer(invocation -> sample);
    }

    private Map<String, Object> preview() throws Exception {
        return json(
                multipart("/api/v1/imports/monobank/preview")
                        .file(new MockMultipartFile("file", "statement.pdf", "application/pdf", new byte[] {1}))
                        .header("Authorization", auth),
                200);
    }

    private Map<String, Object> confirm(Map<String, Object> preview) throws Exception {
        return json(
                postJson(
                        "/api/v1/imports/previews/" + preview.get("id") + "/confirm",
                        Map.of("operations", List.of()),
                        auth),
                200);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> preview) {
        return (Map<String, Object>) preview.get("summary");
    }

    private List<Transaction> userTransactions() {
        return tx.execute(status -> {
            List<Transaction> all = transactions.findAll().stream()
                    .filter(t -> t.getUserId().equals(user.getId()))
                    .toList();
            all.forEach(t -> {
                t.getAccount().getId();
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
        Map<String, Object> preview = preview();
        assertThat(preview).containsEntry("source", "MONOBANK");
        assertThat(summary(preview)).containsEntry("accountsCreated", 1).containsEntry("transactionsImported", 5);

        Map<String, Object> result = confirm(preview);

        assertThat(result).containsEntry("transactionsImported", 5);
        Account account =
                accounts.findByUserIdOrderBySortOrderAscIdAsc(user.getId()).getFirst();
        assertThat(account.getName()).isEqualTo("Monobank *1234");
        assertThat(account.getType()).isEqualTo(AccountType.CARD);
        assertThat(account.getCardNumbers()).containsExactly("1234");
        assertThat(accountService.balances(user.getId()).get(account.getId())).isEqualByComparingTo("1500");

        List<Transaction> imported = userTransactions();
        assertThat(imported).allSatisfy(t -> assertThat(t.getSource()).isEqualTo(TransactionSource.MONOBANK));
        Transaction topUp = onDate(imported, "2026-10-03");
        assertThat(topUp.getPayee().getName()).isEqualTo("Київстар");
        assertThat(topUp.getComment()).isEqualTo("+380000000000");
        assertThat(topUp.getMcc()).isEqualTo((short) 4814);
        Transaction aws = onDate(imported, "2026-10-04");
        assertThat(aws.getOriginalAmount()).isEqualByComparingTo("1");
        assertThat(aws.getOriginalCurrency()).isEqualTo("USD");
        Transaction income = onDate(imported, "2026-10-02");
        assertThat(income.getType()).isEqualTo(TransactionType.INCOME);
        assertThat(income.getAmount()).isEqualByComparingTo("2000");
    }

    @Test
    void repeatedStatementAddsNothing() throws Exception {
        confirm(preview());

        Map<String, Object> second = preview();

        assertThat(summary(second))
                .containsEntry("accountsCreated", 0)
                .containsEntry("transactionsImported", 0)
                .containsEntry("transactionsSkipped", 5);
        confirm(second);
        assertThat(userTransactions()).hasSize(5);
    }

    @Test
    void operationsSyncedThroughTheConnectionAreDuplicates() throws Exception {
        Account card = new Account(user.getId(), "Monobank black *1234", AccountType.CARD, "UAH");
        card.setCardNumbers(List.of("1234"));
        Account saved = accounts.save(card);
        synced(saved, "api-1", TransactionType.EXPENSE, "1190.80", "2026-10-05");
        synced(saved, "api-2", TransactionType.INCOME, "2000", "2026-10-02");

        Map<String, Object> preview = preview();

        assertThat(summary(preview))
                .containsEntry("accountsCreated", 0)
                .containsEntry("transactionsMatched", 2)
                .containsEntry("transactionsImported", 3);
        confirm(preview);
        List<Transaction> all = userTransactions();
        assertThat(all).hasSize(5);
        assertThat(all).allSatisfy(t -> assertThat(t.getAccount().getId()).isEqualTo(saved.getId()));
    }

    @Test
    void oneSyncedOperationStandsForOneRecordOnly() throws Exception {
        sample = sample.replace(
                        "AWS EMEA 5999 -41.20 -1.00 USD 41.20 0.00 0.00 2 690.80",
                        "Сільпо 5411 -1 190.80 -1" + " 190.80 UAH — 0.00 0.00 1 541.20")
                .replace("04.10.2026\n09:00:00", "05.10.2026\n09:00:00")
                .replace(
                        "Сільпо 5411 -1 190.80 -1 190.80 UAH — 0.00 0.00 1 500.00",
                        "Сільпо 5411 -1 190.80 -1 190.80" + " UAH — 0.00 0.00 350.40");
        Account card = new Account(user.getId(), "Monobank *1234", AccountType.CARD, "UAH");
        card.setCardNumbers(List.of("1234"));
        Account saved = accounts.save(card);
        synced(saved, "api-1", TransactionType.EXPENSE, "1190.80", "2026-10-05");

        Map<String, Object> preview = preview();

        // Two purchases of the same amount on the same day: the synced one stands for one of them
        assertThat(summary(preview)).containsEntry("transactionsMatched", 1).containsEntry("transactionsImported", 4);
    }

    @Test
    void detectsTheSourceOfAFile() throws Exception {
        assertThat(detect(new byte[] {1})).isEqualTo("MONOBANK");
        sample = "АКЦІОНЕРНЕ ТОВАРИСТВО КОМЕРЦІЙНИЙ БАНК «ПриватБанк»\nВиписка по картці";
        assertThat(detect(new byte[] {1})).isEqualTo("PRIVATBANK");
        sample = "Some other document";
        assertThat(detect(new byte[] {1})).isEqualTo("UNKNOWN");
        assertThat(detect(new byte[] {'P', 'K', 3, 4})).isEqualTo("FREEDOM");
        assertThat(detect("zm_dump_2011,1,,\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ZENMONEY");
    }

    private String detect(byte[] content) throws Exception {
        Map<String, Object> response = json(
                multipart("/api/v1/imports/detect")
                        .file(new MockMultipartFile("file", "shared", "application/octet-stream", content))
                        .header("Authorization", auth),
                200);
        return (String) response.get("source");
    }

    /** An operation brought in through the Monobank connection: it has the bank's id. */
    private void synced(Account account, String bankId, TransactionType type, String amount, String date) {
        Transaction transaction = new Transaction(user.getId());
        transaction.setSource(TransactionSource.MONOBANK);
        transaction.setExternalId(bankId);
        transaction.setBankId(bankId);
        transaction.setType(type);
        transaction.setDate(LocalDate.parse(date));
        transaction.setAccount(account);
        transaction.setAmount(new BigDecimal(amount));
        transactions.save(transaction);
    }
}
