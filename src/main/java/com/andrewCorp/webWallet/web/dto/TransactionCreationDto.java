package com.andrewCorp.webWallet.web.dto;


import com.andrewCorp.webWallet.persistence.model.Transaction;

import java.util.ArrayList;
import java.util.List;

public class TransactionCreationDto {

    private List<Transaction> transactions;

    public TransactionCreationDto() {
        this.transactions = new ArrayList<>();
    }

    public TransactionCreationDto(List<Transaction> transactions) {
        this.transactions = transactions;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
    }

    public void addTransactions(Transaction transaction) {
        this.transactions.add(transaction);
    }
}
