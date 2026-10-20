package com.andrewcorp.webwallet.imports.zenmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.user.User;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

class ZenMoneyCsvImportTest extends AbstractIntegrationTest {

    /** The shape of the CSV export of Zen Money: a dump line, blank lines, the header and decimal commas. */
    private static final String CSV = "﻿zm_dump_2011,1792507078,,,\"4,0\",\n\n\n"
            + "date,categoryName,payee,comment,outcomeAccountName,outcome,outcomeCurrencyShortTitle,incomeAccountName,"
            + "income,incomeCurrencyShortTitle,createdDate,changedDate\n"
            + "1970-01-01,Correction,,\"Автоматичне коригування балансу\",Готівка,\"488,93\",UAH,,,,"
            + "\"2023-04-23 14:16:02\",\"2026-10-19 08:52:14\"\n"
            + "2026-09-01,\"Food / Cafe, Trip\",Kavarnia,\"Coffee, \"\"to go\"\"\",Готівка,\"95,50\",UAH,,,,"
            + "\"2026-09-01 09:00:00\",\"2026-09-01 09:00:00\"\n"
            + "2026-09-01,\"Food / Cafe, Trip\",Kavarnia,\"Coffee, \"\"to go\"\"\",Готівка,\"95,50\",UAH,,,,"
            + "\"2026-09-01 09:00:00\",\"2026-09-01 09:00:00\"\n"
            + "2026-09-05,Salary,Employer,,,,,Card,\"50 000,00\",UAH,\"2026-09-05 10:00:00\",\"2026-09-05 10:00:00\"\n"
            + "2026-09-10,,,,Dollars,\"100,00\",USD,Card,\"4150,00\",UAH,\"2026-09-10 12:00:00\",\"2026-09-10 12:00:00\"\n";

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private TransactionRepository transactions;

    private User user;
    private String auth;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
    }

    private Map<String, Object> importCsv() throws Exception {
        return json(
                multipart("/api/v1/imports/zenmoney/file")
                        .file(new MockMultipartFile(
                                "file", "zen.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", auth),
                200);
    }

    private List<Transaction> userTransactions() {
        return transactions.findAll().stream()
                .filter(t -> t.getUserId().equals(user.getId()))
                .sorted(Comparator.comparing(Transaction::getDate))
                .toList();
    }

    @Test
    void importsTheCsvExportOfTheApp() throws Exception {
        Map<String, Object> result = importCsv();

        assertThat(result).containsEntry("accountsCreated", 3).containsEntry("transactionsImported", 5);
        assertThat(accounts.findByUserIdOrderBySortOrderAscIdAsc(user.getId()))
                .extracting(Account::getName, Account::getCurrency)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Готівка", "UAH"),
                        org.assertj.core.groups.Tuple.tuple("Card", "UAH"),
                        org.assertj.core.groups.Tuple.tuple("Dollars", "USD"));
        List<Category> userCategories = categories.findByUserIdOrderBySortOrderAscNameAsc(user.getId());
        Category food = userCategories.stream()
                .filter(c -> c.getName().equals("Food"))
                .findFirst()
                .orElseThrow();
        assertThat(userCategories)
                .filteredOn(c -> c.getName().equals("Cafe"))
                .singleElement()
                .satisfies(cafe -> assertThat(cafe.getParentId()).isEqualTo(food.getId()));

        List<Transaction> imported = userTransactions();
        // The balance correction without a date of its own takes the date it was made
        assertThat(imported.getFirst().getDate()).isEqualTo(LocalDate.of(2023, 4, 23));
        assertThat(imported.getFirst().getAmount()).isEqualByComparingTo("488.93");
        // Two identical coffees are two operations
        assertThat(imported)
                .filteredOn(t -> t.getDate().equals(LocalDate.of(2026, 9, 1)))
                .hasSize(2)
                .allSatisfy(t -> {
                    assertThat(t.getType()).isEqualTo(TransactionType.EXPENSE);
                    assertThat(t.getAmount()).isEqualByComparingTo("95.50");
                    assertThat(t.getComment()).isEqualTo("Coffee, \"to go\"");
                });
        assertThat(imported)
                .filteredOn(t -> t.getType() == TransactionType.INCOME)
                .singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo(new BigDecimal("50000")));
        assertThat(imported)
                .filteredOn(t -> t.getType() == TransactionType.TRANSFER)
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.getAmount()).isEqualByComparingTo("100");
                    assertThat(t.getToAmount()).isEqualByComparingTo("4150");
                });
    }

    @Test
    void importingTheSameFileAgainAddsNothing() throws Exception {
        importCsv();

        Map<String, Object> again = importCsv();

        assertThat(again).containsEntry("accountsCreated", 0).containsEntry("transactionsImported", 0);
        assertThat(userTransactions()).hasSize(5);
    }

    @Test
    void previewsTheCsvLikeTheOtherExports() throws Exception {
        Map<String, Object> preview = json(
                multipart("/api/v1/imports/zenmoney/file/preview")
                        .file(new MockMultipartFile(
                                "file", "zen.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", auth),
                200);

        assertThat(preview).containsKey("id");
        assertThat(userTransactions()).isEmpty();
    }

    @Test
    void rejectsAFileThatIsNeitherCsvNorJson() throws Exception {
        json(
                multipart("/api/v1/imports/zenmoney/file")
                        .file(new MockMultipartFile(
                                "file",
                                "zen.csv",
                                "text/csv",
                                "date,amount\n2026-09-01,10\n".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", auth),
                400);
    }
}
