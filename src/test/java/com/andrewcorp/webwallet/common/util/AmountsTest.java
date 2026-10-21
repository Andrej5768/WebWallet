package com.andrewcorp.webwallet.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AmountsTest {

    @Test
    void thousandsWithAnyKindOfSpace() {
        assertThat(Amounts.parse("1 234,56")).isEqualByComparingTo("1234.56");
        assertThat(Amounts.parse("1 234,56")).isEqualByComparingTo("1234.56");
        assertThat(Amounts.parse("1 234.56")).isEqualByComparingTo("1234.56");
        assertThat(Amounts.parse("1\n234,56")).isEqualByComparingTo("1234.56");
        assertThat(Amounts.parse("-150")).isEqualByComparingTo("-150");
    }

    @Test
    void notANumber() {
        assertThatThrownBy(() -> Amounts.parse("кава")).isInstanceOf(NumberFormatException.class);
    }
}
