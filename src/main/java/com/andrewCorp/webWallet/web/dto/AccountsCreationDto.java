package com.andrewCorp.webWallet.web.dto;


import com.andrewCorp.webWallet.persistence.model.Account;

import java.util.ArrayList;
import java.util.List;

public class AccountsCreationDto {

    private List<Account> accounts;

    public AccountsCreationDto() {
        this.accounts = new ArrayList<>();
    }

    public AccountsCreationDto(List<Account> accounts) {
        this.accounts = accounts;
    }

    public List<Account> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<Account> accounts) {
        this.accounts = accounts;
    }

    public void addAccounts(Account account) {
        this.accounts.add(account);
    }
}
