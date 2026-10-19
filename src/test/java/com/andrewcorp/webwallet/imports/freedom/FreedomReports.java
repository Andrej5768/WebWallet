package com.andrewcorp.webwallet.imports.freedom;

import com.andrewcorp.webwallet.imports.TestXlsx;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports of Freedom Finance as its cabinet exports them, made up: a purchase and a sale of a US fund, a fund of the
 * Moscow exchange with its fee charged in dollars, currency exchanges, a dividend with its tax worded differently, a
 * deposit in euros, a withdrawal with its blocking rows, fees and a row of an unknown kind.
 *
 * <p>Afterwards: SCHD 6, FXUS 5; 416.22 USD, 0 EUR, 0 RUB.
 */
final class FreedomReports {

    private FreedomReports() {}

    static byte[] trades() {
        return trades(List.of(
                row(101L, at(2, 1, 10), at(2, 2, 0), "SCHD.US", " Buy ", 10, 25, 250, 0, 2),
                row(102L, at(2, 2, 10), at(2, 2, 0), "EUR/USD", " Sell ", 100, 1.1, 110, 0, 0),
                row(104L, at(2, 3, 5), at(2, 3, 0), "RUR/USD", " Buy ", 300, 0.0125, 3.75, 0, 0),
                row(103L, at(2, 3, 6), at(2, 4, 0), "FXUS", " Buy ", 5, 60, 300, 0, 0.2),
                row(105L, at(2, 10, 15), at(2, 11, 0), "SCHD.US", " Sell ", 4, 30, 120, 18, 2)));
    }

    static byte[] trades(List<List<Object>> rows) {
        List<List<Object>> all = new ArrayList<>();
        all.add(row(
                "Number",
                "Date",
                "Settlement Date",
                "Ticker",
                "Direction",
                "Quantity",
                "Price",
                "Amount",
                "Profit",
                "Fee"));
        all.addAll(rows);
        return TestXlsx.of(all);
    }

    static byte[] cashFlows() {
        return TestXlsx.of(List.of(
                row("Operation №", "Date", "Direction", "Comment", "Amount", "Currency"),
                row(13L, at(3, 5, 9), "Mystery", "Something new", 1, "USD"),
                row(
                        12L,
                        at(3, 4, 2),
                        " Negative cash balance fee ",
                        " Fee for negative cash balance USD ",
                        -0.1,
                        "USD"),
                row(11L, at(3, 3, 12), " Other fees ", " Commission for withdrawal of funds 7 USD ", -7, "USD"),
                row(10L, at(3, 3, 12), "Bank transfer", " Debit. Withdrawal of funds on behalf of 1 ", -50, "USD"),
                row(9L, at(3, 2, 9), "Blocking", " Lock. Withdrawal of funds on behalf of 1 ", -50, "USD"),
                row(8L, at(3, 2, 9), "Blocking", " Lock. Withdrawal of funds on behalf of 1 ", 50, "USD"),
                row(
                        7L,
                        at(3, 1, 14),
                        "Taxes",
                        "Corporate action tax on paper ( SCHD.US ), cutoff date 2030-02-25 . Tax rate 15",
                        -0.23,
                        "USD"),
                row(
                        6L,
                        at(3, 1, 14),
                        "Dividends",
                        "Dividends on paper ( Schwab US Dividend Equity ETF (SCHD.US) ), cut date 2030-02-25 . For one"
                                + " paper 0.25 USD . Balance on the record date is 6 ",
                        1.5,
                        "USD"),
                row(
                        5L,
                        at(2, 10, 15),
                        " Trading fee ",
                        "(Trade 105 sell SCHD.US ) Market: usa, security type: funds, commission currency: USD,"
                                + " trade currency: USD.",
                        -2,
                        "USD"),
                row(
                        4L,
                        at(2, 3, 6),
                        " Trading fee ",
                        "(Trade 103 buy FXUS ) Market: russia, security type: stocks, commission currency: USD,"
                                + " trade currency: RUR.",
                        -0.2,
                        "USD"),
                row(
                        3L,
                        at(2, 1, 10),
                        " Trading fee ",
                        "(Trade 101 buy SCHD.US ) Market: usa, security type: funds, commission currency: USD,"
                                + " trade currency: USD.",
                        -2,
                        "USD"),
                row(2L, at(1, 11, 9), "Card payment", " Deposit with a bank card ", 100, "EUR"),
                row(1L, at(1, 10, 9), "Card payment", "Deposit with a bank card", 500, "USD")));
    }

    static List<Object> row(Object... cells) {
        return List.of(cells);
    }

    static LocalDateTime at(int month, int day, int hour) {
        return LocalDateTime.of(2030, month, day, hour, 0);
    }
}
