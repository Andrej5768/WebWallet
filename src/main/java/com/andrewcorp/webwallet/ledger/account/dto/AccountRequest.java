package com.andrewcorp.webwallet.ledger.account.dto;

import com.andrewcorp.webwallet.common.validation.CurrencyCode;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * @param currency     required on creation, ignored on update: the currency of an account cannot change
 * @param inBudget     whether the money of the account is distributed by the envelope budget; defaults by type
 * @param interestRate annual rate in percent
 */
public record AccountRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull AccountType type,
        @CurrencyCode String currency,
        Boolean inBudget,
        Boolean archived,
        @Digits(integer = 15, fraction = 4) BigDecimal openingBalance,
        @PositiveOrZero @Digits(integer = 15, fraction = 4) BigDecimal creditLimit,
        @PositiveOrZero @Digits(integer = 5, fraction = 4) BigDecimal interestRate,
        Integer sortOrder) {
}
