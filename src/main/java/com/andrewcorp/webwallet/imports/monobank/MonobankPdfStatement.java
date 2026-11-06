package com.andrewcorp.webwallet.imports.monobank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A parsed Monobank card statement ("Рух коштів по картці", a signed PDF from the Monobank app).
 *
 * @param cardLast4 last 4 digits of the card from the header
 * @param records   operations, newest first as in the statement
 * @param warnings  results of the consistency checks; empty when the statement was parsed completely
 */
public record MonobankPdfStatement(
        String cardLast4,
        String currency,
        LocalDate periodFrom,
        LocalDate periodTo,
        BigDecimal creditLimit,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        List<Record> records,
        List<String> warnings) {

    /**
     * @param payee           the details without a phone or card number at the end: the merchant, the person or
     *                        the jar; null when there are no details
     * @param details         all lines of the details joined by spaces
     * @param cardAmount      signed amount in the card currency
     * @param operationAmount signed amount in the operation currency
     * @param balanceAfter    card balance after the operation
     */
    public record Record(
            LocalDate date,
            LocalTime time,
            String payee,
            String details,
            short mcc,
            BigDecimal cardAmount,
            BigDecimal operationAmount,
            String operationCurrency,
            BigDecimal fee,
            BigDecimal cashback,
            BigDecimal balanceAfter) {}
}
