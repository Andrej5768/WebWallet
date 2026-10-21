package com.andrewcorp.webwallet.imports.privatbank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.util.Amounts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Parses the text of a PrivatBank card statement ("Виписка по картці" from Privat24).
 *
 * <p>Records follow each other newest first: date and time lines, the card glued to the contract number (no card
 * for bank charges), the contract date, details on several lines, the amount in the operation currency and a line
 * with the currency, the card amount, the fee, the discount and the balance. Numbers use a space as the thousands
 * separator, which can become a line break. The result is checked against the record count, the balance chain and
 * the header totals.</p>
 */
@Component
public class PrivatStatementParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    // Possessive quantifiers and a bounded length of details keep matching linear on malformed input
    private static final String NUMBER = "(-?\\d{1,3}(?:[ \\n]\\d{3})*+,\\d\\d)";
    private static final int MAX_DETAILS_LENGTH = 1000;
    private static final String HEADER_NUMBER = "(-?\\d{1,3}(?: \\d{3})*,\\d\\d)";
    private static final String RECORD_START = "\\d\\d\\.\\d\\d\\.\\d{4}\\n\\d\\d:\\d\\d\\n";

    /*
     * PDFBox puts the card and the currency on lines of their own; other extractors glue the card to the contract
     * number and the currency to the card amount. Both layouts are accepted.
     */
    private static final Pattern RECORD = Pattern.compile(
            "(\\d\\d\\.\\d\\d\\.\\d{4})\\n(\\d\\d:\\d\\d)\\n(?:(\\d{6}\\*+\\d{4})\\n?)?Угода[^\\n]*\\nвід[^\\n]*\\n"
                    // Details may not run into the next record
                    + "((?:(?!\\n" + RECORD_START + ").){0," + MAX_DETAILS_LENGTH + "}?)[ \\n]" + NUMBER
                    + "\\n([A-Z]{3})[ \\n]"
                    + NUMBER + "[ \\n]" + NUMBER + "[ \\n]" + NUMBER + "[ \\n]" + NUMBER,
            Pattern.DOTALL);
    private static final Pattern RECORD_ANCHOR = Pattern.compile("(?m)^\\d\\d\\.\\d\\d\\.\\d{4}\\n\\d\\d:\\d\\d$");
    /** Bank requisites at the bottom of every page and the table header repeated on the next one. */
    private static final Pattern PAGE_BREAK =
            Pattern.compile("АКЦІОНЕРНЕ ТОВАРИСТВО.*?(?:Залишок після\\nоперації\\n|\\z)", Pattern.DOTALL);

    private static final String TABLE_HEADER_END = "Залишок після\nоперації\n";

    private static final Pattern CARD = Pattern.compile("Інформація по картці:\\s*\\d{6}\\*+(\\d{4})");
    private static final Pattern CURRENCY = Pattern.compile("Валюта рахунку:\\s*([A-Z]{3})");
    private static final Pattern PERIOD =
            Pattern.compile("Період:\\s*(\\d\\d\\.\\d\\d\\.\\d{4})\\s*[—–-]\\s*(\\d\\d\\.\\d\\d\\.\\d{4})");
    private static final Pattern CREDIT_LIMIT = Pattern.compile("Кредитний ліміт:\\s*" + HEADER_NUMBER);
    private static final Pattern OPENING = Pattern.compile("Баланс на початок періоду:\\s*" + HEADER_NUMBER);
    private static final Pattern CLOSING = Pattern.compile("Баланс на кінець періоду:\\s*" + HEADER_NUMBER);
    private static final Pattern SPENT = Pattern.compile("Усього витрат:\\s*" + HEADER_NUMBER);
    private static final Pattern RECEIVED = Pattern.compile("Усього надходжень:\\s*" + HEADER_NUMBER);

    public PrivatStatement parse(String rawText) {
        try {
            return parseText(rawText);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
    }

    private PrivatStatement parseText(String rawText) {
        String text = rawText.replace("\r\n", "\n").replace('\u00A0', ' ').replace('\u202F', ' ');
        Matcher card = CARD.matcher(text);
        if (!card.find()) {
            // Loan contracts and other documents from the same bank are PDFs too
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        String currency = group(CURRENCY, text);
        if (currency == null) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        Matcher period = PERIOD.matcher(text);
        boolean hasPeriod = period.find();

        String body = body(text);
        List<PrivatStatement.Record> records = new ArrayList<>();
        Matcher matcher = RECORD.matcher(body);
        while (matcher.find()) {
            records.add(new PrivatStatement.Record(
                    LocalDate.parse(matcher.group(1), DATE),
                    LocalTime.parse(matcher.group(2)),
                    matcher.group(3),
                    matcher.group(4).replaceAll("\\s+", " ").trim(),
                    number(matcher.group(5)),
                    matcher.group(6),
                    number(matcher.group(7)),
                    number(matcher.group(8)),
                    number(matcher.group(9)),
                    number(matcher.group(10))));
        }

        BigDecimal opening = headerNumber(OPENING, text);
        BigDecimal closing = headerNumber(CLOSING, text);
        List<String> warnings = new ArrayList<>();
        checkRecordCount(body, records, warnings);
        checkBalanceChain(records, warnings);
        checkTotals(records, opening, closing, headerNumber(SPENT, text), headerNumber(RECEIVED, text), warnings);

        return new PrivatStatement(
                card.group(1),
                currency,
                hasPeriod ? LocalDate.parse(period.group(1), DATE) : null,
                hasPeriod ? LocalDate.parse(period.group(2), DATE) : null,
                headerNumber(CREDIT_LIMIT, text),
                opening,
                closing,
                List.copyOf(records),
                List.copyOf(warnings));
    }

    /** The table without the header and the page breaks. */
    private static String body(String text) {
        int tableStart = text.indexOf(TABLE_HEADER_END);
        String table = tableStart < 0 ? text : text.substring(tableStart + TABLE_HEADER_END.length());
        // Trailing spaces and empty lines are dropped line by line: a regular expression would be quadratic on long
        // runs of spaces
        return PAGE_BREAK
                .matcher(table)
                .replaceAll("")
                .lines()
                .map(String::stripTrailing)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n", "", "\n"));
    }

    /** Every record starts with a date line and a time line; each of them must have been parsed. */
    private static void checkRecordCount(String body, List<PrivatStatement.Record> records, List<String> warnings) {
        long anchors = RECORD_ANCHOR.matcher(body).results().count();
        if (anchors != records.size()) {
            warnings.add("Parsed " + records.size() + " of " + anchors
                    + " operations: the statement has an unsupported format");
        }
    }

    /** With records newest first, the balance before each operation is the balance after the next older one. */
    private static void checkBalanceChain(List<PrivatStatement.Record> records, List<String> warnings) {
        for (int i = 0; i + 1 < records.size(); i++) {
            PrivatStatement.Record newer = records.get(i);
            PrivatStatement.Record older = records.get(i + 1);
            BigDecimal expected = older.balanceAfter().add(newer.cardAmount());
            boolean matches = expected.compareTo(newer.balanceAfter()) == 0
                    || expected.subtract(newer.fee()).compareTo(newer.balanceAfter()) == 0;
            if (!matches) {
                warnings.add("Balance does not add up between the operations of " + older.date() + " " + older.time()
                        + " and " + newer.date() + " " + newer.time() + ": an operation may be missing");
            }
        }
    }

    private static void checkTotals(
            List<PrivatStatement.Record> records,
            BigDecimal opening,
            BigDecimal closing,
            BigDecimal spent,
            BigDecimal received,
            List<String> warnings) {
        if (records.isEmpty()) {
            return;
        }
        PrivatStatement.Record oldest = records.get(records.size() - 1);
        if (opening != null
                && oldest.balanceAfter().subtract(oldest.cardAmount()).compareTo(opening) != 0) {
            warnings.add("The opening balance differs from the statement header");
        }
        if (closing != null && records.get(0).balanceAfter().compareTo(closing) != 0) {
            warnings.add("The closing balance differs from the statement header");
        }
        BigDecimal negative = records.stream()
                .map(PrivatStatement.Record::cardAmount)
                .filter(amount -> amount.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal positive = records.stream()
                .map(PrivatStatement.Record::cardAmount)
                .filter(amount -> amount.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (spent != null && negative.compareTo(spent) != 0) {
            warnings.add("Total spent differs from the statement header");
        }
        if (received != null && positive.compareTo(received) != 0) {
            warnings.add("Total received differs from the statement header");
        }
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static BigDecimal headerNumber(Pattern pattern, String text) {
        String value = group(pattern, text);
        return value == null ? null : number(value);
    }

    /** "1 234,56" (the thousands space may be a line break) to 1234.56. */
    static BigDecimal number(String text) {
        return Amounts.parse(text);
    }
}
