package com.andrewcorp.webwallet.imports.privatbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PrivatStatementParserTest {

    private final PrivatStatementParser parser = new PrivatStatementParser();
    private String sample;

    @BeforeEach
    void setUp() throws Exception {
        sample = new ClassPathResource("imports/privat/statement-sample.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                // Git may check the file out with CRLF line endings on Windows
                .replace("\r\n", "\n");
    }

    @Test
    void parsesHeader() {
        PrivatStatement statement = parser.parse(sample);

        assertThat(statement.cardLast4()).isEqualTo("1234");
        assertThat(statement.currency()).isEqualTo("UAH");
        assertThat(statement.periodFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(statement.periodTo()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(statement.creditLimit()).isEqualByComparingTo("70000");
        assertThat(statement.openingBalance()).isEqualByComparingTo("-27572.53");
        assertThat(statement.closingBalance()).isEqualByComparingTo("-54698.84");
    }

    /** The sample mixes the layout of PDFBox (card and currency on separate lines) with the glued one. */
    @Test
    void parsesEveryRecordVariantAndPassesAllChecks() {
        PrivatStatement statement = parser.parse(sample);

        assertThat(statement.warnings()).isEmpty();
        assertThat(statement.records()).hasSize(6);

        PrivatStatement.Record groceries = statement.records().get(0);
        assertThat(groceries.date()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(groceries.time()).isEqualTo(LocalTime.of(15, 47));
        assertThat(groceries.card()).isEqualTo("414960******1234");
        assertThat(groceries.cardAmount()).isEqualByComparingTo("-3060.51");
        assertThat(groceries.balanceAfter()).isEqualByComparingTo("-54698.84");
        assertThat(groceries.bankCategory()).isEqualTo("Супермаркети та продукти");
        assertThat(groceries.details())
                .contains("отримувач PRODUKTY SHOP, банк")
                .doesNotContain("\n");

        // A second (virtual) card of the same account; a purchase in another currency
        PrivatStatement.Record steam = statement.records().get(2);
        assertThat(steam.card()).isEqualTo("462705******9876");
        assertThat(steam.operationAmount()).isEqualByComparingTo("-20");
        assertThat(steam.operationCurrency()).isEqualTo("USD");
        assertThat(steam.cardAmount()).isEqualByComparingTo("-904.98");

        // A page break inside the details and a fee wrapped in the middle of the number
        PrivatStatement.Record wrapped = statement.records().get(3);
        assertThat(wrapped.details())
                .isEqualTo("Платежі за реквізитами, УКРАЇНА, отримувач ТОВ БУДМАТЕРІАЛИ, банк"
                        + " отримувача -, код ІД НБУ -, Коментар до платежу: оплата за рахунком 15");
        assertThat(wrapped.fee()).isEqualByComparingTo("1223.43");
        assertThat(wrapped.cardAmount()).isEqualByComparingTo("-42737.73");

        // The amount on the same line as short details
        PrivatStatement.Record income = statement.records().get(4);
        assertThat(income.details()).isEqualTo("Перекази, УКРАЇНА, від IVANENKO PETRO");
        assertThat(income.cardAmount()).isEqualByComparingTo("32000");

        // A bank charge without a card
        PrivatStatement.Record interest = statement.records().get(5);
        assertThat(interest.card()).isNull();
        assertThat(interest.bankCategory()).startsWith("Списання відсотків");
    }

    @Test
    void reportsMissingRecordsAndBrokenBalances() {
        // A record in an unknown format: no line with the amounts
        String broken = sample.replace("-3 060,51\nUAH\n-3 060,51 0,00 0,00 -54 698,84\n", "щось незрозуміле\n");

        PrivatStatement statement = parser.parse(broken);

        assertThat(statement.records()).hasSize(5);
        assertThat(statement.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Parsed 5 of 6"))
                .anySatisfy(warning -> assertThat(warning).contains("closing balance"))
                .anySatisfy(warning -> assertThat(warning).contains("Total spent"));
    }

    @Test
    void reportsBalanceChainBreak() {
        String gap = sample.replace("UAH -10 300,00 0,00 0,00 -51 638,33", "UAH -10 300,00 0,00 0,00 -51 000,00");

        assertThat(parser.parse(gap).warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Balance does not add up"));
    }

    @Test
    void rejectsOtherDocuments() {
        assertThatThrownBy(() -> parser.parse("Кредитний договір № 123\nСума кредиту: 10 000,00"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_IMPORT_FILE));
    }
}
