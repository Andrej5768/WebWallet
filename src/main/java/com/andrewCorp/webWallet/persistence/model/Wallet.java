package com.andrewCorp.webWallet.persistence.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "wallet")
public class Wallet {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false)
    private Long id;

    @OneToMany(mappedBy = "wallet")
    private List<Account> accounts = new ArrayList<>();

    @OneToMany(mappedBy = "wallet")
    private List<Category> categories = new ArrayList<>();

    @OneToMany(mappedBy = "wallet")
    private List<Transaction> transactions = new ArrayList<>();

    public Wallet() {
    }

    public List<Category> getCategories() {
        return categories;
    }

    public void setCategories(List<Category> categories) {
        this.categories = categories;
    }

    public List<Account> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<Account> accounts) {
        this.accounts = accounts;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public List<Account> setAccountsAndReturnAccounts(List<Account> accounts) {
        this.accounts = accounts;
        return this.accounts;
    }

}