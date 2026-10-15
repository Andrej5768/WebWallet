package com.andrewcorp.webwallet.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TransactionSpecificationsTest {

    @Test
    void parseAmountAcceptsTheUsualWritings() {
        assertThat(TransactionSpecifications.parseAmount("1284.50")).isEqualByComparingTo("1284.5");
        assertThat(TransactionSpecifications.parseAmount("1284,50")).isEqualByComparingTo("1284.5");
        assertThat(TransactionSpecifications.parseAmount("1 284,50")).isEqualByComparingTo("1284.5");
        assertThat(TransactionSpecifications.parseAmount("1 284,50")).isEqualByComparingTo("1284.5");
        // The sign is ignored: amounts are stored positive
        assertThat(TransactionSpecifications.parseAmount("-42")).isEqualByComparingTo(new BigDecimal(42));
    }

    @Test
    void parseAmountRejectsOtherText() {
        assertThat(TransactionSpecifications.parseAmount("kava")).isNull();
        assertThat(TransactionSpecifications.parseAmount("1.2.3")).isNull();
        assertThat(TransactionSpecifications.parseAmount("1,284.50")).isNull();
        assertThat(TransactionSpecifications.parseAmount("12%")).isNull();
    }
}
