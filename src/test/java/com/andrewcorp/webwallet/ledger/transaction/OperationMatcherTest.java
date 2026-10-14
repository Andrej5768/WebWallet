package com.andrewcorp.webwallet.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OperationMatcherTest {

    @Test
    void bankIdsLoseThePrefixesOfZenMoney() {
        assertThat(OperationMatcher.normalizeBankId("[monobank][reg]-abc")).isEqualTo("abc");
        assertThat(OperationMatcher.normalizeBankId("[privat]xyz")).isEqualTo("xyz");
        assertThat(OperationMatcher.normalizeBankId(" plain-id ")).isEqualTo("plain-id");
    }

    @Test
    void blankOrMissingBankIdsAreNull() {
        assertThat(OperationMatcher.normalizeBankId(null)).isNull();
        assertThat(OperationMatcher.normalizeBankId("[monobank][reg]-")).isNull();
        assertThat(OperationMatcher.normalizeBankId("   ")).isNull();
    }

    @Test
    void longBankIdsAreCut() {
        assertThat(OperationMatcher.normalizeBankId("x".repeat(300))).hasSize(200);
    }
}
