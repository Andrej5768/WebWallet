package com.andrewcorp.webwallet.imports.freedom;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.imports.XlsxReader;
import com.andrewcorp.webwallet.imports.freedom.FreedomReport.CashFlow;
import com.andrewcorp.webwallet.imports.freedom.FreedomReport.Trade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads the XLSX reports of Freedom Finance: each file is recognized by its header, so they may come in any order.
 */
@Component
public class FreedomReportParser {

    private static final List<String> TRADE_COLUMNS =
            List.of("number", "date", "ticker", "direction", "quantity", "price", "amount", "fee");
    private static final List<String> CASH_COLUMNS =
            List.of("operation", "date", "direction", "comment", "amount", "currency");

    /** The reports in the files, one of each kind at most. */
    public FreedomReport parse(List<byte[]> files) {
        List<Trade> trades = null;
        List<CashFlow> cashFlows = null;
        for (byte[] file : files) {
            List<List<String>> rows = XlsxReader.firstSheet(file);
            Map<String, Integer> header = rows.isEmpty() ? Map.of() : header(rows.get(0));
            if (header.keySet().containsAll(TRADE_COLUMNS)) {
                if (trades != null) {
                    throw ApiException.invalid("files", "error.import.brokerFileTwice");
                }
                trades = trades(rows, header);
            } else if (header.keySet().containsAll(CASH_COLUMNS)) {
                if (cashFlows != null) {
                    throw ApiException.invalid("files", "error.import.brokerFileTwice");
                }
                cashFlows = cashFlows(rows, header);
            } else {
                throw ApiException.invalid("files", "error.import.brokerFile");
            }
        }
        return new FreedomReport(trades != null ? trades : List.of(), cashFlows != null ? cashFlows : List.of());
    }

    /** Column names in lower case, letters and spaces only, e.g. {@code Operation №} → operation. */
    private static Map<String, Integer> header(List<String> row) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < row.size(); i++) {
            String name = row.get(i);
            if (name != null) {
                String key = name.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L} ]", "")
                        .trim()
                        .replaceAll("\\s+", " ");
                columns.putIfAbsent(key, i);
            }
        }
        return columns;
    }

    private static List<Trade> trades(List<List<String>> rows, Map<String, Integer> header) {
        List<Trade> trades = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (blank(row)) {
                continue;
            }
            String direction = text(row, header, "direction").toLowerCase(Locale.ROOT);
            if (!direction.equals("buy") && !direction.equals("sell")) {
                throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
            }
            trades.add(new Trade(
                    number(row, header, "number"),
                    time(row, header),
                    text(row, header, "ticker"),
                    direction.equals("buy"),
                    decimal(row, header, "quantity"),
                    decimal(row, header, "price"),
                    decimal(row, header, "amount"),
                    decimal(row, header, "fee").abs()));
        }
        return trades;
    }

    private static List<CashFlow> cashFlows(List<List<String>> rows, Map<String, Integer> header) {
        List<CashFlow> flows = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (blank(row)) {
                continue;
            }
            flows.add(new CashFlow(
                    number(row, header, "operation"),
                    time(row, header),
                    text(row, header, "direction"),
                    text(row, header, "comment"),
                    decimal(row, header, "amount"),
                    text(row, header, "currency").toUpperCase(Locale.ROOT)));
        }
        return flows;
    }

    private static boolean blank(List<String> row) {
        return row == null || row.stream().allMatch(cell -> cell == null || cell.isBlank());
    }

    private static String text(List<String> row, Map<String, Integer> header, String column) {
        int index = header.get(column);
        String value = index < row.size() ? row.get(index) : null;
        return value != null ? value.trim().replaceAll("\\s+", " ") : "";
    }

    private static long number(List<String> row, Map<String, Integer> header, String column) {
        try {
            return new BigDecimal(text(row, header, column)).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
    }

    private static BigDecimal decimal(List<String> row, Map<String, Integer> header, String column) {
        String value = text(row, header, column);
        try {
            return value.isEmpty() ? BigDecimal.ZERO : new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
    }

    private static LocalDateTime time(List<String> row, Map<String, Integer> header) {
        String value = text(row, header, "date");
        try {
            return XlsxReader.dateTime(value);
        } catch (NumberFormatException e) {
            try {
                // Exported as text: "2026-09-29 14:21:11"
                return LocalDateTime.parse(value.replace(' ', 'T'));
            } catch (RuntimeException parse) {
                throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
            }
        }
    }
}
