package com.andrewcorp.webwallet.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.andrewcorp.webwallet.telegram.QuickEntryParser.AccountName;
import com.andrewcorp.webwallet.telegram.QuickEntryParser.Mention;
import com.andrewcorp.webwallet.telegram.QuickEntryParser.Parsed;
import com.andrewcorp.webwallet.telegram.QuickEntryParser.Role;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuickEntryParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final List<AccountName> ACCOUNTS = List.of(
            new AccountName(1L, "Monobank Чорна"),
            new AccountName(2L, "Monobank Біла"),
            new AccountName(3L, "ПриватБанк"),
            new AccountName(4L, "Готівка"));

    private static Parsed parse(String text) {
        return QuickEntryParser.parse(text, ACCOUNTS, TODAY).orElseThrow();
    }

    @Test
    void expenseWithDescription() {
        Parsed parsed = parse("150 кава");

        assertThat(parsed.amount()).isEqualByComparingTo("150");
        assertThat(parsed.income()).isFalse();
        assertThat(parsed.date()).isEqualTo(TODAY);
        assertThat(parsed.mentions()).isEmpty();
        assertThat(parsed.description()).isEqualTo("кава");
    }

    @Test
    void incomeWithPlusAndDecimalComma() {
        assertThat(parse("+5000 зарплата").income()).isTrue();
        assertThat(parse("89,90 таксі").amount()).isEqualByComparingTo(new BigDecimal("89.90"));
        assertThat(parse("кава 45.5грн").amount()).isEqualByComparingTo("45.5");
    }

    @Test
    void dates() {
        assertThat(parse("320 таксі вчора").date()).isEqualTo(TODAY.minusDays(1));
        assertThat(parse("320 таксі вчора").description()).isEqualTo("таксі");
        assertThat(parse("100 обід 18.10").date()).isEqualTo(LocalDate.of(2026, 10, 18));
        // A day still ahead this year is of the last one
        assertThat(parse("100 обід 25.12").date()).isEqualTo(LocalDate.of(2025, 12, 25));
        assertThat(parse("100 обід 01.02.25").date()).isEqualTo(LocalDate.of(2025, 2, 1));
    }

    @Test
    void twoAccountsMakeATransferWithoutArrows() {
        Parsed parsed = parse("2000 приват готівку");

        assertThat(parsed.mentions()).extracting(Mention::accountIds).containsExactly(List.of(3L), List.of(4L));
        assertThat(parsed.description()).isEmpty();
    }

    @Test
    void arrowsAndLinkWordsAreOptional() {
        assertThat(parse("2000 приват→готівка").mentions()).hasSize(2);
        assertThat(parse("2000 приват -> готівка").mentions()).hasSize(2);
        assertThat(parse("2000 з привату на готівку").mentions()).hasSize(2);
        assertThat(parse("2000 з привату на готівку").description()).isEmpty();
    }

    @Test
    void ambiguousAccountWordListsEveryAccount() {
        Parsed parsed = parse("300 моно таксі");

        assertThat(parsed.mentions()).singleElement().satisfies(mention -> {
            assertThat(mention.word()).isEqualTo("моно");
            assertThat(mention.accountIds()).containsExactly(1L, 2L);
        });
        assertThat(parsed.description()).isEqualTo("таксі");
    }

    @Test
    void latinAndCyrillicNamesMatch() {
        assertThat(parse("50 mono кава").mentions()).hasSize(1);
        assertThat(parse("50 чорна кава").mentions().get(0).accountIds()).containsExactly(1L);
        assertThat(parse("50 privat кава").mentions().get(0).accountIds()).containsExactly(3L);
    }

    @Test
    void wordsThatNameNoAccountStayInTheDescription() {
        assertThat(parse("150 кава з молоком").description()).isEqualTo("кава з молоком");
        assertThat(parse("2000 банкомат").mentions()).isEmpty();
    }

    @Test
    void amountWrittenWithSpaces() {
        assertThat(parse("1 500 продукти").amount()).isEqualByComparingTo("1500");
        assertThat(parse("1 500 продукти").description()).isEqualTo("продукти");
        assertThat(parse("+12 000,50 зарплата").amount()).isEqualByComparingTo("12000.50");
    }

    @Test
    void dateBeforeTheAmount() {
        Parsed parsed = parse("12.10 150 кава");

        assertThat(parsed.amount()).isEqualByComparingTo("150");
        assertThat(parsed.date()).isEqualTo(LocalDate.of(2026, 10, 12));
        // A single number is the amount, even when it looks like a date
        assertThat(parse("20.10 таксі").amount()).isEqualByComparingTo("20.10");
    }

    @Test
    void currencyOfTheAmount() {
        assertThat(parse("100 грн таксі").currency()).isEqualTo("UAH");
        assertThat(parse("100 грн таксі").description()).isEqualTo("таксі");
        assertThat(parse("20$ обід").currency()).isEqualTo("USD");
        assertThat(parse("$20 обід").currency()).isEqualTo("USD");
        assertThat(parse("150 кава").currency()).isNull();
    }

    @Test
    void wordsBeforeTheAccountsSayTheDirection() {
        Parsed parsed = parse("500 на готівку з привату");

        assertThat(parsed.mentions()).extracting(Mention::role).containsExactly(Role.TO, Role.FROM);
        assertThat(parse("500 приват готівка").mentions())
                .extracting(Mention::role)
                .containsExactly(Role.UNSAID, Role.UNSAID);
    }

    @Test
    void noAmount() {
        assertThat(QuickEntryParser.parse("кава", ACCOUNTS, TODAY)).isEmpty();
        assertThat(QuickEntryParser.parse("0 кава", ACCOUNTS, TODAY)).isEmpty();
    }
}
