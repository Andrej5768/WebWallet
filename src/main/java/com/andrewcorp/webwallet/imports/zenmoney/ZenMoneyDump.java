package com.andrewcorp.webwallet.imports.zenmoney;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response of the Zen Money sync API ({@code POST /v8/diff/}) with only the fields that are imported.
 * See https://github.com/zenmoney/ZenPlugins/wiki/ZenMoney-API
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenMoneyDump(
        Long serverTimestamp,
        List<Instrument> instrument,
        List<User> user,
        List<Account> account,
        List<Tag> tag,
        List<Merchant> merchant,
        List<Transaction> transaction) {

    /** A currency; {@code shortTitle} is the ISO code. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Instrument(int id, String shortTitle) {
    }

    /** {@code currency} is the instrument id of the base currency. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, Integer currency) {
    }

    /**
     * @param type   cash, ccard, checking, loan, deposit, emoney or debt
     * @param syncID last 4 digits of the cards of a bank-synced account
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(
            String id,
            Integer instrument,
            String title,
            String type,
            BigDecimal balance,
            BigDecimal creditLimit,
            Boolean inBalance,
            Boolean archive,
            Boolean savings,
            List<String> syncID,
            BigDecimal percent) {
    }

    /** A category; only one level of nesting through {@code parent}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(String id, String title, String parent, Boolean showIncome, Boolean showOutcome, Boolean archive) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Merchant(String id, String title) {
    }

    /**
     * An expense has the same account on both sides and only {@code outcome}, an income only {@code income}; a
     * transfer has different accounts, each side in the currency of its account. {@code opIncome} / {@code opOutcome}
     * hold the original amount of a foreign currency operation.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transaction(
            String id,
            String date,
            Boolean deleted,
            String incomeAccount,
            BigDecimal income,
            String outcomeAccount,
            BigDecimal outcome,
            BigDecimal opIncome,
            Integer opIncomeInstrument,
            BigDecimal opOutcome,
            Integer opOutcomeInstrument,
            List<String> tag,
            String merchant,
            String payee,
            String comment,
            String incomeBankID,
            String outcomeBankID) {
    }
}
