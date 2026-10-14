package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.AbstractIntegrationTest;
import com.andrewCorp.webWallet.persistence.dao.AccountRepository;
import com.andrewCorp.webWallet.persistence.dao.TransactionRepository;
import com.andrewCorp.webWallet.persistence.enums.TransactionType;
import com.andrewCorp.webWallet.persistence.model.Account;
import com.andrewCorp.webWallet.persistence.model.Transaction;
import com.andrewCorp.webWallet.persistence.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class WalletControllerTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private EntityManager entityManager;

    private User user;
    private User otherUser;
    private Account account;
    private Account otherAccount;
    private Transaction transaction;
    private Transaction otherTransaction;

    @BeforeEach
    void setUp() {
        user = registerUser();
        otherUser = registerUser();
        account = accountRepository.save(new Account("Cash", new BigDecimal("100.00"), user.getWallet()));
        otherAccount = accountRepository.save(new Account("Other cash", new BigDecimal("50.00"), otherUser.getWallet()));
        transaction = saveTransaction(account, user);
        otherTransaction = saveTransaction(otherAccount, otherUser);
    }

    private Transaction saveTransaction(Account account, User owner) {
        Transaction transaction = new Transaction(account, new BigDecimal("10.00"), "remark", Date.valueOf("2026-01-15"));
        transaction.setType(TransactionType.EXPENSE);
        transaction.setWallet(owner.getWallet());
        return transactionRepository.save(transaction);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // accounts

    @Test
    void anonymousUserIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/accounts/all"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void showAllAccountsListsOnlyOwnAccounts() throws Exception {
        mockMvc.perform(get("/accounts/all").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("accounts"))
                .andExpect(model().attribute("accounts", contains(account)));
    }

    @Test
    void showAccountsCreateFormHasThreeEmptyAccounts() throws Exception {
        mockMvc.perform(get("/accounts/create").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("createAccountsForm"))
                .andExpect(model().attribute("form", org.hamcrest.Matchers.hasProperty("accounts", hasSize(3))));
    }

    @Test
    void showAccountsEditFormContainsOwnAccounts() throws Exception {
        mockMvc.perform(get("/accounts/edit").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("editAccountsForm"))
                .andExpect(model().attribute("form", org.hamcrest.Matchers.hasProperty("accounts", contains(account))));
    }

    @Test
    void saveAllCreatesAccountsInCurrentWallet() throws Exception {
        mockMvc.perform(post("/accounts/saveAll").with(as(user))
                        .param("accounts[0].name", "Card")
                        .param("accounts[0].amount", "20")
                        .param("accounts[1].name", "Deposit")
                        .param("accounts[1].amount", "30"))
                .andExpect(redirectedUrl("/accounts/all"));

        assertThat(accountRepository.findAllByWalletId(user.getWallet().getId()))
                .extracting(Account::getName)
                .containsExactlyInAnyOrder("Cash", "Card", "Deposit");
    }

    @Test
    void saveAllUpdatesOwnAccountAndKeepsWallet() throws Exception {
        mockMvc.perform(post("/accounts/saveAll").with(as(user))
                        .param("accounts[0].id", account.getId().toString())
                        .param("accounts[0].name", "Renamed")
                        .param("accounts[0].amount", "1"))
                .andExpect(redirectedUrl("/accounts/all"));
        flushAndClear();

        Account updated = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getWallet().getId()).isEqualTo(user.getWallet().getId());
    }

    @Test
    void saveAllRejectsForeignAccount() throws Exception {
        mockMvc.perform(post("/accounts/saveAll").with(as(user))
                        .param("accounts[0].id", otherAccount.getId().toString())
                        .param("accounts[0].name", "Stolen")
                        .param("accounts[0].amount", "0"))
                .andExpect(status().isNotFound());
        flushAndClear();

        assertThat(accountRepository.findById(otherAccount.getId()).orElseThrow().getName()).isEqualTo("Other cash");
    }

    @Test
    void saveAccountCreatesAccountInCurrentWallet() throws Exception {
        mockMvc.perform(post("/accounts/save").with(as(user))
                        .param("name", "Savings")
                        .param("amount", "500"))
                .andExpect(redirectedUrl("/accounts/all"));

        Account saved = accountRepository.findByNameAndWalletId("Savings", user.getWallet().getId());
        assertThat(saved).isNotNull();
        assertThat(saved.getAmount()).isEqualByComparingTo("500");
    }

    // transactions

    @Test
    void showAllTransactionsListsOnlyOwnTransactions() throws Exception {
        mockMvc.perform(get("/transactions/all").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("transactions"))
                .andExpect(model().attribute("transactions", contains(transaction)))
                .andExpect(model().attribute("accounts", contains(account)))
                .andExpect(model().attributeExists("types", "categories"));
    }

    @Test
    void showAllTransactionsViaPost() throws Exception {
        mockMvc.perform(post("/transactions/all").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("transactions"))
                .andExpect(model().attribute("transactions", contains(transaction)));
    }

    @Test
    void showAccountTransactionsForOwnAccount() throws Exception {
        mockMvc.perform(get("/account/{id}/transactions", account.getId()).with(as(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("transactions"))
                .andExpect(model().attribute("transactions", contains(transaction)));
    }

    @Test
    void showAccountTransactionsForForeignAccountIsNotFound() throws Exception {
        mockMvc.perform(get("/account/{id}/transactions", otherAccount.getId()).with(as(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void showTransactionsCreateFormRenders() throws Exception {
        mockMvc.perform(get("/transactions/create").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("createTransactionsForm"))
                .andExpect(model().attribute("accounts", contains(account)))
                .andExpect(model().attributeExists("types"));
    }

    @Test
    void showTransactionsEditFormContainsOwnTransactions() throws Exception {
        mockMvc.perform(get("/transactions/edit").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(view().name("editTransactionsForm"))
                .andExpect(model().attribute("form", org.hamcrest.Matchers.hasProperty("transactions", contains(transaction))))
                .andExpect(model().attribute("accounts", contains(account)));
    }

    @Test
    void updateTransactionChangesFields() throws Exception {
        Account card = accountRepository.save(new Account("Card", BigDecimal.ZERO, user.getWallet()));

        mockMvc.perform(post("/transactions/update/{id}", transaction.getId()).with(as(user))
                        .param("date", "2026-02-01")
                        .param("type", "INCOME")
                        .param("amount", "42")
                        .param("account", card.getId().toString()))
                .andExpect(redirectedUrl("/transactions/all"));
        flushAndClear();

        Transaction updated = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(updated.getType()).isEqualTo(TransactionType.INCOME);
        assertThat(updated.getAmount()).isEqualByComparingTo("42");
        assertThat(updated.getAccount().getId()).isEqualTo(card.getId());
    }

    @Test
    void updateForeignTransactionIsNotFound() throws Exception {
        mockMvc.perform(post("/transactions/update/{id}", otherTransaction.getId()).with(as(user))
                        .param("amount", "0"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateTransactionWithForeignAccountIsNotFound() throws Exception {
        mockMvc.perform(post("/transactions/update/{id}", transaction.getId()).with(as(user))
                        .param("account", otherAccount.getId().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void saveAllTransactionsUpdatesOwnTransactions() throws Exception {
        mockMvc.perform(post("/transactions/saveAll").with(as(user))
                        .param("transactions[0].id", transaction.getId().toString())
                        .param("transactions[0].date", "2026-03-01")
                        .param("transactions[0].type", "TRANSFER")
                        .param("transactions[0].amount", "7")
                        .param("transactions[0].account", account.getId().toString())
                        .param("transactions[0].remark", "edited"))
                .andExpect(redirectedUrl("/transactions/all"));
        flushAndClear();

        Transaction updated = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(updated.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(updated.getRemark()).isEqualTo("edited");
        assertThat(updated.getWallet().getId()).isEqualTo(user.getWallet().getId());
    }

    @Test
    void saveAllTransactionsRejectsForeignTransaction() throws Exception {
        mockMvc.perform(post("/transactions/saveAll").with(as(user))
                        .param("transactions[0].id", otherTransaction.getId().toString())
                        .param("transactions[0].amount", "0"))
                .andExpect(status().isNotFound());
    }

    @Test
    void saveTransactionPersistsAllFields() throws Exception {
        mockMvc.perform(post("/transactions/save").with(as(user))
                        .param("date", "2026-04-10")
                        .param("category", "Food")
                        .param("amount", "12.5")
                        .param("account", "Cash")
                        .param("transactionType", "EXPENSE")
                        .param("remark", "lunch"))
                .andExpect(redirectedUrl("/transactions/all"));
        flushAndClear();

        List<Transaction> transactions = transactionRepository.findTransactionsByWalletId(user.getWallet().getId());
        Transaction saved = transactions.stream().filter(t -> "lunch".equals(t.getRemark())).findFirst().orElseThrow();
        assertThat(saved.getAmount()).isEqualByComparingTo("12.5");
        assertThat(saved.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(saved.getAccount().getId()).isEqualTo(account.getId());
    }

    @Test
    void saveTransactionDoesNotUseForeignAccountWithSameName() throws Exception {
        mockMvc.perform(post("/transactions/save").with(as(user))
                        .param("date", "2026-04-10")
                        .param("amount", "1")
                        .param("account", "Other cash")
                        .param("transactionType", "INCOME"))
                .andExpect(redirectedUrl("/transactions/all"));
        flushAndClear();

        assertThat(transactionRepository.findTransactionsByWalletId(user.getWallet().getId()))
                .noneMatch(t -> t.getAccount() != null && t.getAccount().getId().equals(otherAccount.getId()));
    }

    @Test
    void deleteOwnTransaction() throws Exception {
        mockMvc.perform(get("/transactions/delete/{id}", transaction.getId()).with(as(user)))
                .andExpect(redirectedUrl("/transactions/all"));

        assertThat(transactionRepository.findById(transaction.getId())).isEmpty();
    }

    @Test
    void deleteForeignTransactionIsNotFound() throws Exception {
        mockMvc.perform(get("/transactions/delete/{id}", otherTransaction.getId()).with(as(user)))
                .andExpect(status().isNotFound());

        assertThat(transactionRepository.findById(otherTransaction.getId())).isPresent();
    }
}
