package com.andrewcorp.webwallet.ledger.transaction.dto;

import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.time.LocalDate;

/**
 * Optional filters of the transaction list; all given filters must match.
 *
 * @param accountId  source or target account
 * @param categoryId category or, for a top-level category, any of its subcategories
 * @param q          text in the payee name, the comment or a split comment, ignoring case; a number also matches
 *                   the amount
 */
public record TransactionFilter(
        LocalDate from,
        LocalDate to,
        Long accountId,
        Long categoryId,
        TransactionType type,
        Long payeeId,
        Long tagId,
        String q) {}
