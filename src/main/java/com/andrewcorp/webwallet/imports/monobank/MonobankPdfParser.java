package com.andrewcorp.webwallet.imports.monobank;

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
import org.springframework.stereotype.Component;

/**
 * Parses the text of a Monobank card statement.
 *
 * <p>Records follow each other newest first: a date line, a time line, the details on one or more lines and a
 * closing line with the MCC, the card amount, the operation amount and currency, the rate (a dash in the card
 * currency), the fee, the cashback and the balance; the last line of the details may share the closing line. The
 * table header repeats on every page. The result is checked against the balance chain and the header totals.</p>
 */
@Component
public class MonobankPdfParser {

    /** The bank that issues Monobank cards; its name heads the statement. */
    public static final String BANK_MARKER = "УНІВЕРСАЛ БАНК";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String NUMBER = "-?\\d{1,3}(?: \\d{3})*+\\.\\d\\d";
    private static final Pattern DATE_LINE = Pattern.compile("\\d\\d\\.\\d\\d\\.\\d{4}");
    private static final Pattern TIME_LINE = Pattern.compile("\\d\\d:\\d\\d:\\d\\d");
    private static final Pattern CLOSING_LINE = Pattern.compile("(?:(.{1,500}?) )?(\\d{4}) (" + NUMBER + ") (" + NUMBER
            + ") ([A-Z]{3}) (?:—|-|\\d+(?:\\.\\d+)?) (" + NUMBER + ") (" + NUMBER + ") (" + NUMBER + ")");
    private static final Pattern CARD =
            Pattern.compile("Інформація по картці:\\s*(?:\\d{4} ?\\*{4} ?\\*{4} ?|\\d{6}\\*{6})(\\d{4})");
    private static final Pattern PERIOD =
            Pattern.compile("Період:\\s*(\\d\\d\\.\\d\\d\\.\\d{4})\\s*[—–-]\\s*(\\d\\d\\.\\d\\d\\.\\d{4})");
    private static final Pattern CREDIT_LIMIT = Pattern.compile("Кредитний ліміт[^:]*:\\s*(" + NUMBER + ") [A-Z]{3}");
    private static final Pattern OPENING = Pattern.compile("Баланс на початок періоду:\\s*(" + NUMBER + ") ([A-Z]{3})");
    private static final Pattern CLOSING = Pattern.compile("Баланс на кінець періоду:\\s*(" + NUMBER + ") [A-Z]{3}");
    private static final Pattern SPENT = Pattern.compile("Сума витрат за період:\\s*(" + NUMBER + ") [A-Z]{3}");
    private static final Pattern RECEIVED = Pattern.compile("Сума зарахувань за період:\\s*(" + NUMBER + ") [A-Z]{3}");
    /** The last line of the table header, repeated on every page. */
    private static final String HEADER_END = "операції";

    private static final String HEADER_END_BEFORE = "після";
    private static final String HEADER_START = "Дата i час";
    /** Lines after the table: the signature of the bank. */
    private static final List<String> FOOTER = List.of("Операційний директор", "Документ підписано");

    private static final int MAX_DETAIL_LINES = 10;
    /** A phone or card number after the name, e.g. the number of a mobile top-up: not part of the payee. */
    private static final Pattern TRAILING_NUMBER = Pattern.compile("\\s\\+?\\d[\\d*]{6,}$");

    public MonobankPdfStatement parse(String rawText) {
        try {
            return parseText(rawText);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
    }

    private MonobankPdfStatement parseText(String rawText) {
        String text = rawText.replace("\r\n", "\n").replace('\u00A0', ' ').replace('\u202F', ' ');
        Matcher card = CARD.matcher(text);
        Matcher opening = OPENING.matcher(text);
        if (!text.contains(BANK_MARKER) || !card.find() || !opening.find()) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        String currency = opening.group(2);
        Matcher period = PERIOD.matcher(text);
        boolean hasPeriod = period.find();

        List<String> warnings = new ArrayList<>();
        List<MonobankPdfStatement.Record> records = records(table(text), warnings);
        BigDecimal openingBalance = Amounts.parse(opening.group(1));
        BigDecimal closingBalance = headerNumber(CLOSING, text);
        checkBalanceChain(records, warnings);
        checkTotals(
                records,
                openingBalance,
                closingBalance,
                headerNumber(SPENT, text),
                headerNumber(RECEIVED, text),
                warnings);
        return new MonobankPdfStatement(
                card.group(1),
                currency,
                hasPeriod ? LocalDate.parse(period.group(1), DATE) : null,
                hasPeriod ? LocalDate.parse(period.group(2), DATE) : null,
                headerNumber(CREDIT_LIMIT, text),
                openingBalance,
                closingBalance,
                List.copyOf(records),
                List.copyOf(warnings));
    }

    /** The lines of the table without the headers of the pages and the signature at the end. */
    private static List<String> table(String text) {
        List<String> lines = new ArrayList<>();
        boolean inHeader = false;
        boolean started = false;
        String previous = "";
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            String before = previous;
            previous = line;
            if (line.equals(HEADER_START)) {
                inHeader = true;
                started = true;
                continue;
            }
            if (inHeader) {
                // The header ends with "Залишок / після / операції"; other header lines say "операції" too
                inHeader = !(line.equals(HEADER_END) && before.equals(HEADER_END_BEFORE));
                continue;
            }
            if (!started) {
                continue;
            }
            if (FOOTER.stream().anyMatch(line::startsWith)) {
                break;
            }
            lines.add(line);
        }
        return lines;
    }

    private static List<MonobankPdfStatement.Record> records(List<String> lines, List<String> warnings) {
        List<MonobankPdfStatement.Record> records = new ArrayList<>();
        int skipped = 0;
        int i = 0;
        while (i < lines.size()) {
            if (i + 1 >= lines.size()
                    || !DATE_LINE.matcher(lines.get(i)).matches()
                    || !TIME_LINE.matcher(lines.get(i + 1)).matches()) {
                i++;
                continue;
            }
            LocalDate date = LocalDate.parse(lines.get(i), DATE);
            LocalTime time = LocalTime.parse(lines.get(i + 1));
            List<String> details = new ArrayList<>();
            int j = i + 2;
            Matcher closing = null;
            while (j < lines.size() && details.size() <= MAX_DETAIL_LINES) {
                Matcher candidate = CLOSING_LINE.matcher(lines.get(j));
                if (candidate.matches()) {
                    closing = candidate;
                    break;
                }
                if (DATE_LINE.matcher(lines.get(j)).matches()) {
                    break;
                }
                details.add(lines.get(j));
                j++;
            }
            if (closing == null) {
                skipped++;
                i = j;
                continue;
            }
            if (closing.group(1) != null) {
                details.add(closing.group(1));
            }
            String joined = String.join(" ", details);
            Matcher number = TRAILING_NUMBER.matcher(joined);
            String payee = number.find() && number.start() > 0
                    ? joined.substring(0, number.start()).trim()
                    : joined;
            records.add(new MonobankPdfStatement.Record(
                    date,
                    time,
                    payee.isEmpty() ? null : payee,
                    joined,
                    Short.parseShort(closing.group(2)),
                    Amounts.parse(closing.group(3)),
                    Amounts.parse(closing.group(4)),
                    closing.group(5),
                    Amounts.parse(closing.group(6)),
                    Amounts.parse(closing.group(7)),
                    Amounts.parse(closing.group(8))));
            i = j + 1;
        }
        if (skipped > 0) {
            warnings.add("Parsed " + records.size() + " of " + (records.size() + skipped)
                    + " operations: the statement has an unsupported format");
        }
        return records;
    }

    /** With records newest first, the balance before each operation is the balance after the next older one. */
    private static void checkBalanceChain(List<MonobankPdfStatement.Record> records, List<String> warnings) {
        for (int i = 0; i + 1 < records.size(); i++) {
            MonobankPdfStatement.Record newer = records.get(i);
            MonobankPdfStatement.Record older = records.get(i + 1);
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
            List<MonobankPdfStatement.Record> records,
            BigDecimal opening,
            BigDecimal closing,
            BigDecimal spent,
            BigDecimal received,
            List<String> warnings) {
        if (records.isEmpty()) {
            return;
        }
        MonobankPdfStatement.Record oldest = records.getLast();
        // The bank's own header can be off by the operations of the first minutes of the period: the opening balance
        // is only checked when the header adds up by itself
        boolean headerAddsUp = closing != null
                && spent != null
                && received != null
                && opening.add(received).subtract(spent.abs()).compareTo(closing) == 0;
        if (headerAddsUp && oldest.balanceAfter().subtract(oldest.cardAmount()).compareTo(opening) != 0) {
            warnings.add("The opening balance differs from the statement header");
        }
        if (closing != null && records.getFirst().balanceAfter().compareTo(closing) != 0) {
            warnings.add("The closing balance differs from the statement header");
        }
        BigDecimal negative = records.stream()
                .map(MonobankPdfStatement.Record::cardAmount)
                .filter(amount -> amount.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate();
        BigDecimal positive = records.stream()
                .map(MonobankPdfStatement.Record::cardAmount)
                .filter(amount -> amount.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (spent != null && negative.compareTo(spent.abs()) != 0) {
            warnings.add("Total spent differs from the statement header");
        }
        if (received != null && positive.compareTo(received) != 0) {
            warnings.add("Total received differs from the statement header");
        }
    }

    private static BigDecimal headerNumber(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Amounts.parse(matcher.group(1)) : null;
    }
}
