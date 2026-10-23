package com.andrewcorp.webwallet.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.auth.TokenService;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.auth.twofactor.Totp;
import com.andrewcorp.webwallet.bank.BankConnection;
import com.andrewcorp.webwallet.bank.BankConnectionRepository;
import com.andrewcorp.webwallet.bank.BankProvider;
import com.andrewcorp.webwallet.common.crypto.SecretCipher;
import com.andrewcorp.webwallet.common.web.ClientInfo;
import com.andrewcorp.webwallet.investment.Instrument;
import com.andrewcorp.webwallet.investment.InstrumentRepository;
import com.andrewcorp.webwallet.investment.InstrumentType;
import com.andrewcorp.webwallet.investment.InvestmentOperation;
import com.andrewcorp.webwallet.investment.InvestmentOperationRepository;
import com.andrewcorp.webwallet.investment.OperationType;
import com.andrewcorp.webwallet.investment.Valuation;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeRepository;
import com.andrewcorp.webwallet.ledger.tag.Tag;
import com.andrewcorp.webwallet.ledger.tag.TagRepository;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.Transaction.SplitData;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

class AccountDataTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private PayeeRepository payees;

    @Autowired
    private TagRepository tags;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private BankConnectionRepository connections;

    @Autowired
    private InstrumentRepository instruments;

    @Autowired
    private InvestmentOperationRepository operations;

    @Autowired
    private AccountDeletionService deletion;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private SecretCipher cipher;

    private User user;
    private String auth;
    private User other;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        other = createVerifiedUser();
        fillData(user);
        fillData(other);
    }

    private void fillData(User owner) {
        Long userId = owner.getId();
        Account card = accounts.save(new Account(userId, "Card, black", AccountType.CARD, "UAH"));
        Category food = categories.save(new Category(userId, null, "Food", CategoryKind.EXPENSE));
        Category cafe = new Category(userId, food.getId(), "Cafe", CategoryKind.EXPENSE);
        categories.save(cafe);
        Payee shop = payees.save(new Payee(userId, "Shop"));
        Tag tag = tags.save(new Tag(userId, "trip"));
        Transaction expense = new Transaction(userId);
        expense.setType(TransactionType.EXPENSE);
        expense.setDate(LocalDate.of(2026, 9, 1));
        expense.setAccount(card);
        expense.setAmount(new BigDecimal("100"));
        expense.setPayee(shop);
        expense.setComment("Coffee \"to go\"");
        expense.replaceSplits(List.of(new SplitData(cafe, new BigDecimal("100"), null)));
        expense.replaceTags(Set.of(tag));
        transactions.save(expense);
        connections.save(new BankConnection(userId, BankProvider.MONOBANK, "encrypted-token", "secret-" + userId));

        Account broker = accounts.save(new Account(userId, "Broker", AccountType.INVESTMENT, "USD"));
        Instrument own = new Instrument(userId, "FLAT-1", InstrumentType.OTHER, "USD", Valuation.MANUAL);
        own.setName("Flat");
        instruments.save(own);
        InvestmentOperation buy = new InvestmentOperation(userId);
        buy.setAccount(broker);
        buy.setInstrument(own);
        buy.setType(OperationType.BUY);
        buy.setDate(LocalDate.of(2026, 9, 2));
        buy.setQuantity(BigDecimal.ONE);
        buy.setPrice(new BigDecimal("1000"));
        buy.setAmount(new BigDecimal("1000"));
        buy.setFee(BigDecimal.ZERO);
        buy.setCurrency("USD");
        operations.save(buy);
    }

    private Map<String, String> unzip(byte[] archive) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    // export

    @Test
    @SuppressWarnings("unchecked")
    void exportsEverythingOfTheUserWithoutSecrets() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        get("/api/v1/me/export").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string(
                                "Content-Disposition",
                                org.hamcrest.Matchers.startsWith("attachment; filename=\"webwallet-export-")))
                .andReturn()
                .getResponse();

        Map<String, String> files = unzip(response.getContentAsByteArray());
        assertThat(files).containsOnlyKeys("webwallet.json", "transactions.csv");

        Map<String, Object> document = jsonMapper.readValue(files.get("webwallet.json"), Map.class);
        assertThat(document).containsEntry("format", "webwallet-export").containsEntry("version", 1);
        assertThat((Map<String, Object>) document.get("user")).containsEntry("email", user.getEmail());
        Map<String, List<Map<String, Object>>> tables = (Map<String, List<Map<String, Object>>>) document.get("tables");
        assertThat(tables).doesNotContainKeys("users", "refresh_tokens", "user_tokens", "import_previews");
        assertThat(tables.get("accounts"))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row).doesNotContainKey("user_id"))
                .extracting(row -> row.get("name"))
                .containsExactly("Card, black", "Broker");
        assertThat(tables.get("instruments"))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("symbol", "FLAT-1"));
        assertThat(tables.get("investment_operations")).hasSize(1);
        assertThat(tables.get("categories")).hasSize(2);
        assertThat(tables.get("transactions")).hasSize(1);
        // Rows that belong to the user through a foreign key
        assertThat(tables.get("transaction_splits")).hasSize(1);
        assertThat(tables.get("transaction_tags")).hasSize(1);
        assertThat(tables.get("bank_connections"))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsKey("provider")
                        .doesNotContainKeys("encrypted_token", "webhook_secret"));

        String csv = files.get("transactions.csv");
        assertThat(csv).startsWith("﻿date,type,account,amount,currency,");
        assertThat(csv.lines()).hasSize(2);
        assertThat(csv.lines().toList().get(1))
                .startsWith("2026-09-01,EXPENSE,\"Card, black\",100.0000,UAH,")
                .contains(",Food / Cafe,Shop,trip,\"Coffee \"\"to go\"\"\"");
    }

    // deletion

    private MockHttpServletResponse requestDeletion(Map<String, Object> body, String authorization) throws Exception {
        return mockMvc.perform(postJson("/api/v1/me/delete", body, authorization))
                .andReturn()
                .getResponse();
    }

    @Test
    void deletionBlocksTheAccountUntilTheUserCancelsAtSignIn() throws Exception {
        MockHttpServletResponse loginResponse = loginResponse(user.getEmail(), PASSWORD);
        assertThat(requestDeletion(Map.of("password", "wrong-password"), auth).getStatus())
                .isEqualTo(400);

        MockHttpServletResponse response = requestDeletion(Map.of("password", PASSWORD), auth);

        assertThat(response.getStatus()).isEqualTo(200);
        Instant at = Instant.parse(jsonMapper
                .readTree(response.getContentAsString())
                .get("deletionScheduledAt")
                .asString());
        assertThat(at)
                .isBetween(
                        Instant.now().plus(Duration.ofDays(13)), Instant.now().plus(Duration.ofDays(15)));
        assertThat(response.getCookie(REFRESH_COOKIE).getMaxAge()).isZero();
        // All sessions end
        mockMvc.perform(refresh(refreshCookie(loginResponse))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("Authorization", auth)).andExpect(status().isUnauthorized());
        assertThat(lastMail().getSubject()).isEqualTo("Your account is going to be deleted");
        assertThat(lastMail().getText()).contains("/restore-account#token=");

        Map<String, Object> blocked =
                json(postJson("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", PASSWORD)), 403);
        assertThat(blocked).containsEntry("code", "ACCOUNT_DELETION_SCHEDULED").containsKey("deletionScheduledAt");

        MockHttpServletResponse restored = mockMvc.perform(
                        postJson("/api/v1/auth/restore", Map.of("token", blocked.get("restoreToken"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        TokenResponse session = tokens(restored);
        assertThat(session.accessToken()).isNotBlank();
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer(session)))
                .andExpect(status().isOk());
        assertThat(users.findById(user.getId()).orElseThrow().getDeletionScheduledAt())
                .isNull();
    }

    @Test
    void nativeRestoreReturnsTheRefreshTokenInTheBody() throws Exception {
        requestDeletion(Map.of("password", PASSWORD), auth);
        Map<String, Object> blocked = json(
                asAndroid(postJson("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", PASSWORD))), 403);

        MockHttpServletResponse restored = mockMvc.perform(
                        asAndroid(postJson("/api/v1/auth/restore", Map.of("token", blocked.get("restoreToken")))))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn()
                .getResponse();
        TokenResponse session = tokens(restored);

        assertThat(session.refreshToken()).isNotBlank();
        mockMvc.perform(asAndroid(postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken()))))
                .andExpect(status().isOk());
    }

    @Test
    void theEmailedLinkCancelsWithoutSigningIn() throws Exception {
        requestDeletion(Map.of("password", PASSWORD), auth);

        Map<String, Object> restored =
                json(postJson("/api/v1/auth/restore", Map.of("token", tokenFromLastMail())), 200);

        assertThat(restored.get("accessToken")).isNull();
        assertThat(login(user.getEmail(), PASSWORD).accessToken()).isNotBlank();
        mockMvc.perform(postJson("/api/v1/auth/restore", Map.of("token", "unknown")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletionNeedsTheTwoFactorCodeWhenItIsOn() throws Exception {
        user.setTotpSecret(cipher.encrypt(Totp.newSecret()), Instant.now());
        users.save(user);

        mockMvc.perform(postJson("/api/v1/me/delete", Map.of("password", PASSWORD), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TWO_FACTOR_CODE"));
        assertThat(users.findById(user.getId()).orElseThrow().getDeletionScheduledAt())
                .isNull();
    }

    @Test
    void anAccountWithoutAPasswordConfirmsWithItsEmail() throws Exception {
        User google = new User("google@test.com", null, "Olena", null, "uk");
        google.setEmailVerified(true);
        google = users.save(google);
        String googleAuth = "Bearer "
                + tokenService.issueTokens(google, ClientInfo.Kind.WEB).accessToken();

        mockMvc.perform(postJson("/api/v1/me/delete", Map.of("confirmEmail", "other@test.com"), googleAuth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("confirmEmail"));
        mockMvc.perform(postJson("/api/v1/me/delete", Map.of("confirmEmail", "Google@Test.com"), googleAuth))
                .andExpect(status().isOk());
        assertThat(lastMail().getSubject()).isEqualTo("Ваш акаунт буде видалено");
    }

    @Test
    void accountsAreDeletedWithTheirDataWhenTheTimeComes() throws Exception {
        requestDeletion(Map.of("password", PASSWORD), auth);

        assertThat(deletion.deleteDue()).isZero();
        TestClock.INSTANCE.set(Instant.now().plus(Duration.ofDays(15)));
        assertThat(deletion.deleteDue()).isEqualTo(1);

        assertThat(users.findById(user.getId())).isEmpty();
        assertThat(accounts.findByUserIdOrderBySortOrderAscIdAsc(user.getId())).isEmpty();
        assertThat(transactions.findAll()).allMatch(t -> t.getUserId().equals(other.getId()));
        assertThat(accounts.findByUserIdOrderBySortOrderAscIdAsc(other.getId())).hasSize(2);
        assertThat(operations.findAll()).allMatch(o -> o.getUserId().equals(other.getId()));
    }
}
