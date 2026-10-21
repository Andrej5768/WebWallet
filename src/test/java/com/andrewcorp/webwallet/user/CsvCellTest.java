package com.andrewcorp.webwallet.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvCellTest {

    @Test
    void formulasBecomeText() {
        assertThat(DataExportService.csvCell("=HYPERLINK(\"https://evil\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"https://evil\"\")\"");
        assertThat(DataExportService.csvCell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(DataExportService.csvCell("+380501234567")).isEqualTo("'+380501234567");
    }

    @Test
    void numbersStayNumbers() {
        assertThat(DataExportService.csvCell("-150.00")).isEqualTo("-150.00");
        assertThat(DataExportService.csvCell("2026-10-21")).isEqualTo("2026-10-21");
        assertThat(DataExportService.csvCell("кава, з молоком")).isEqualTo("\"кава, з молоком\"");
    }
}
