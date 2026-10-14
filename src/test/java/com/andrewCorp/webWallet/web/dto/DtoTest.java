package com.andrewCorp.webWallet.web.dto;

import com.andrewCorp.webWallet.persistence.model.Account;
import com.andrewCorp.webWallet.persistence.model.Category;
import com.andrewCorp.webWallet.persistence.model.Transaction;
import com.andrewCorp.webWallet.web.util.GenericResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DtoTest {

    @Test
    void accountsCreationDto() {
        AccountsCreationDto dto = new AccountsCreationDto();
        Account account = new Account();
        dto.addAccounts(account);
        assertThat(dto.getAccounts()).containsExactly(account);

        dto.setAccounts(new ArrayList<>());
        assertThat(dto.getAccounts()).isEmpty();
        assertThat(new AccountsCreationDto(List.of(account)).getAccounts()).containsExactly(account);
    }

    @Test
    void transactionCreationDto() {
        TransactionCreationDto dto = new TransactionCreationDto();
        Transaction transaction = new Transaction();
        dto.addTransactions(transaction);
        assertThat(dto.getTransactions()).containsExactly(transaction);

        dto.setTransactions(new ArrayList<>());
        assertThat(dto.getTransactions()).isEmpty();
        assertThat(new TransactionCreationDto(List.of(transaction)).getTransactions()).containsExactly(transaction);
    }

    @Test
    void categoryCreationDtoHasDefaultCategories() {
        CategoryCreationDto dto = new CategoryCreationDto();
        assertThat(dto.getAccounts()).extracting(Category::getName).contains("Housing", "Transport", "Food", "Saving");

        Category category = new Category();
        dto.addAccounts(category);
        assertThat(dto.getAccounts()).contains(category);

        dto.setAccounts(new ArrayList<>(List.of(category)));
        assertThat(dto.getAccounts()).containsExactly(category);
        assertThat(new CategoryCreationDto(List.of(category)).getAccounts()).containsExactly(category);
    }

    @Test
    void genericResponse() {
        GenericResponse response = new GenericResponse("message");
        assertThat(response.getMessage()).isEqualTo("message");
        assertThat(response.getError()).isNull();

        response.setMessage("other");
        response.setError("error");
        assertThat(response.getMessage()).isEqualTo("other");
        assertThat(response.getError()).isEqualTo("error");

        GenericResponse withError = new GenericResponse("message", "error");
        assertThat(withError.getError()).isEqualTo("error");
    }
}
