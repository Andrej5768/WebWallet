package com.andrewcorp.webwallet.imports.monobank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andrewcorp.webwallet.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MonobankPdfParserTest {

    private final MonobankPdfParser parser = new MonobankPdfParser();
    private String sample;

    @BeforeEach
    void setUp() throws Exception {
        sample = new ClassPathResource("imports/monobank/statement-sample.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    @Test
    void parsesTheHeaderAndEveryRecordAcrossPages() {
        MonobankPdfStatement statement = parser.parse(sample);

        assertThat(statement.cardLast4()).isEqualTo("1234");
        assertThat(statement.currency()).isEqualTo("UAH");
        assertThat(statement.periodFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(statement.periodTo()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(statement.openingBalance()).isEqualByComparingTo("1000");
        assertThat(statement.closingBalance()).isEqualByComparingTo("1500");
        assertThat(statement.warnings()).isEmpty();
        assertThat(statement.records()).hasSize(5);

        MonobankPdfStatement.Record newest = statement.records().getFirst();
        assertThat(newest.date()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(newest.time()).isEqualTo(LocalTime.of(19, 56, 35));
        assertThat(newest.payee()).isEqualTo("Сільпо");
        assertThat(newest.mcc()).isEqualTo((short) 5411);
        assertThat(newest.cardAmount()).isEqualByComparingTo("-1190.80");
        assertThat(newest.balanceAfter()).isEqualByComparingTo("1500");
    }

    @Test
    void keepsDetailsOnSeveralLinesAndForeignAmounts() {
        MonobankPdfStatement statement = parser.parse(sample);

        MonobankPdfStatement.Record aws = statement.records().get(1);
        assertThat(aws.operationAmount()).isEqualByComparingTo("-1");
        assertThat(aws.operationCurrency()).isEqualTo("USD");
        assertThat(aws.cardAmount()).isEqualByComparingTo("-41.20");

        MonobankPdfStatement.Record topUp = statement.records().get(2);
        assertThat(topUp.payee()).isEqualTo("Київстар");
        assertThat(topUp.details()).isEqualTo("Київстар +380000000000");
        assertThat(MonobankPdfImporter.comment(topUp)).isEqualTo("+380000000000");

        MonobankPdfStatement.Record income = statement.records().get(3);
        assertThat(income.cardAmount()).isEqualByComparingTo("2000");
        assertThat(income.payee()).isEqualTo("Переказ від Тестового Клієнта");
        assertThat(income.details()).isEqualTo("Переказ від Тестового Клієнта");
    }

    @Test
    void warnsWhenTheBalanceDoesNotAddUp() {
        MonobankPdfStatement statement = parser.parse(sample.replace("-100.00 -100.00", "-90.00 -90.00"));

        assertThat(statement.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Balance does not add up"));
    }

    @Test
    void warnsAboutARecordItCannotRead() {
        MonobankPdfStatement statement =
                parser.parse(sample.replace("Steam 5816 -168.00 -168.00 UAH", "Steam 5816 -168.00 UAH"));

        assertThat(statement.records()).hasSize(4);
        assertThat(statement.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Parsed 4 of 5"));
    }

    @Test
    void rejectsOtherDocuments() {
        assertThatThrownBy(() -> parser.parse(sample.replace("УНІВЕРСАЛ БАНК", "ІНШИЙ БАНК")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse("Виписка по картці\nАКЦІОНЕРНЕ ТОВАРИСТВО КОМЕРЦІЙНИЙ БАНК «ПриватБанк»"))
                .isInstanceOf(ApiException.class);
    }
}
