package com.andrewcorp.webwallet.imports.privatbank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A parsed PrivatBank card statement.
 *
 * @param cardLast4      last 4 digits of the card from the header
 * @param records        operations, newest first as in the statement
 * @param warnings       results of the consistency checks; empty when the statement was parsed completely
 */
public record PrivatStatement(
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
     * @param card              masked card of the operation, null for bank charges
     * @param details           operation details as one line
     * @param operationAmount   signed amount in the operation currency
     * @param cardAmount        signed amount in the card currency, fee included
     * @param balanceAfter      card balance after the operation; negative is debt on a credit card
     */
    public record Record(
            LocalDate date,
            LocalTime time,
            String card,
            String details,
            BigDecimal operationAmount,
            String operationCurrency,
            BigDecimal cardAmount,
            BigDecimal fee,
            BigDecimal discount,
            BigDecimal balanceAfter) {

        /** Bank category: the text before the first comma of the details, e.g. "Супермаркети та продукти". */
        public String bankCategory() {
            int comma = details.indexOf(',');
            return (comma < 0 ? details : details.substring(0, comma)).trim();
        }
    }
}
