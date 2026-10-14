package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.AbstractIntegrationTest;
import com.andrewCorp.webWallet.persistence.dao.AccountRepository;
import com.andrewCorp.webWallet.persistence.model.Account;
import com.andrewCorp.webWallet.persistence.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountRestControllerTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EntityManager entityManager;

    private User user;
    private Account account;
    private Account otherAccount;

    @BeforeEach
    void setUp() {
        user = registerUser();
        User otherUser = registerUser();
        account = accountRepository.save(new Account("Cash", new BigDecimal("100.00"), user.getWallet()));
        otherAccount = accountRepository.save(new Account("Other cash", new BigDecimal("50.00"), otherUser.getWallet()));
        // Reload entities from the database, as it happens in a real request
        entityManager.flush();
        entityManager.clear();
        user = userService.findUserByEmail(user.getEmail());
    }

    @Test
    void anonymousRequestIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/accounts"))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void getAllReturnsOnlyOwnAccounts() throws Exception {
        mockMvc.perform(get("/accounts").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Cash"));
    }

    @Test
    void getByIdReturnsOwnAccount() throws Exception {
        mockMvc.perform(get("/accounts/{id}", account.getId()).with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cash"));
    }

    @Test
    void getByIdOfForeignAccountIsNotFound() throws Exception {
        mockMvc.perform(get("/accounts/{id}", otherAccount.getId()).with(as(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void createAddsAccountToCurrentWallet() throws Exception {
        mockMvc.perform(post("/accounts").with(as(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": " + otherAccount.getId() + ", \"name\": \"Card\", \"amount\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Cash", "Card")));

        // The id from the request body must not overwrite someone else's account
        assertThat(accountRepository.findById(otherAccount.getId()).orElseThrow().getName()).isEqualTo("Other cash");
    }

    @Test
    void deleteRemovesOwnAccount() throws Exception {
        mockMvc.perform(delete("/accounts/{id}", account.getId()).with(as(user)))
                .andExpect(status().isOk());

        assertThat(accountRepository.findById(account.getId())).isEmpty();
    }

    @Test
    void deleteOfForeignAccountIsNotFound() throws Exception {
        mockMvc.perform(delete("/accounts/{id}", otherAccount.getId()).with(as(user)))
                .andExpect(status().isNotFound());

        assertThat(accountRepository.findById(otherAccount.getId())).isPresent();
    }
}
