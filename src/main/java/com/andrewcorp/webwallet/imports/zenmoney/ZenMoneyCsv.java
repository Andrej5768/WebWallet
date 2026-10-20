package com.andrewcorp.webwallet.imports.zenmoney;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CSV export of Zen Money ("dump of transactions"): turned into the same {@link ZenMoneyDump} as the sync API
 * gives, so that the import, the preview and the deduplication work the same way.
 *
 * <p>The file starts with a line {@code zm_dump_…} and blank lines, then the header {@code date,categoryName,payee,
 * comment,outcomeAccountName,outcome,outcomeCurrencyShortTitle,incomeAccountName,income,incomeCurrencyShortTitle,
 * createdDate,changedDate}. Amounts use a decimal comma. {@code categoryName} holds the tags of the operation
 * separated by commas, the first being its category, written {@code Parent / Child}. Accounts have only names here:
 * their currency is that of their operations, their type and balance are not exported.
 */
public final class ZenMoneyCsv {

    private static final String HEADER_START = "date,";
    /** Zen Money dates balance corrections without a date of their own at the epoch. */
    private static final String NO_DATE = "1970-01-01";

    private ZenMoneyCsv() {}

    public static boolean looksLikeCsv(byte[] content) {
        String start = new String(content, 0, Math.min(content.length, 64), StandardCharsets.UTF_8)
                .replace("﻿", "")
                .stripLeading();
        return start.startsWith("zm_dump") || start.startsWith(HEADER_START);
    }

    public static ZenMoneyDump parse(byte[] content) {
        List<List<String>> rows = rows(new String(content, StandardCharsets.UTF_8).replace("﻿", ""));
        int headerAt = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isEmpty() && rows.get(i).get(0).equals("date")) {
                headerAt = i;
                break;
            }
        }
        if (headerAt < 0) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        Map<String, Integer> columns = new LinkedHashMap<>();
        List<String> header = rows.get(headerAt);
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).trim(), i);
        }
        for (String required :
                List.of("date", "categoryName", "outcomeAccountName", "outcome", "incomeAccountName", "income")) {
            if (!columns.containsKey(required)) {
                throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
            }
        }

        Builder builder = new Builder();
        for (List<String> row : rows.subList(headerAt + 1, rows.size())) {
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            builder.add(name -> {
                Integer index = columns.get(name);
                return index == null || index >= row.size()
                        ? ""
                        : row.get(index).trim();
            });
        }
        return builder.dump();
    }

    @FunctionalInterface
    private interface Row {
        String get(String column);
    }

    /** Collects the currencies, accounts and tags of the rows and the transactions themselves. */
    private static final class Builder {

        private final Map<String, Integer> instruments = new LinkedHashMap<>();
        private final Map<String, String> accountIds = new LinkedHashMap<>();
        private final Map<String, Integer> accountInstruments = new LinkedHashMap<>();
        private final Map<String, String> accountTitles = new LinkedHashMap<>();
        private final Map<String, ZenMoneyDump.Tag> tags = new LinkedHashMap<>();
        private final List<ZenMoneyDump.Transaction> transactions = new ArrayList<>();
        private final Map<String, Integer> currencyUse = new LinkedHashMap<>();
        /** Identical rows are told apart by their order, which stays the same in a later export. */
        private final Map<String, Integer> seen = new LinkedHashMap<>();

        void add(Row row) {
            String outcomeAccount = account(row.get("outcomeAccountName"), row.get("outcomeCurrencyShortTitle"));
            String incomeAccount = account(row.get("incomeAccountName"), row.get("incomeCurrencyShortTitle"));
            BigDecimal outcome = amount(row.get("outcome"));
            BigDecimal income = amount(row.get("income"));
            if (outcomeAccount == null && incomeAccount == null) {
                return;
            }
            // An expense or an income has the same account on both sides, as in the sync API
            if (outcomeAccount == null) {
                outcomeAccount = incomeAccount;
            }
            if (incomeAccount == null) {
                incomeAccount = outcomeAccount;
            }
            String date = row.get("date");
            if (date.equals(NO_DATE) && row.get("createdDate").length() >= 10) {
                date = row.get("createdDate").substring(0, 10);
            }
            List<String> tagIds = tags(row.get("categoryName"));
            String key = key(row);
            int occurrence = seen.merge(key, 1, Integer::sum);
            transactions.add(new ZenMoneyDump.Transaction(
                    id(key + "#" + occurrence),
                    date,
                    false,
                    incomeAccount,
                    income,
                    outcomeAccount,
                    outcome,
                    null,
                    null,
                    null,
                    null,
                    tagIds.isEmpty() ? null : tagIds,
                    null,
                    blankToNull(row.get("payee")),
                    blankToNull(row.get("comment")),
                    null,
                    null));
        }

        private String account(String name, String currency) {
            if (name.isBlank()) {
                return null;
            }
            String id = accountIds.computeIfAbsent(name, key -> "csv-account-" + (accountIds.size() + 1));
            if (!currency.isBlank()) {
                Integer instrument = instruments.computeIfAbsent(currency, key -> instruments.size() + 1);
                accountInstruments.putIfAbsent(id, instrument);
                currencyUse.merge(currency, 1, Integer::sum);
            }
            accountTitles.putIfAbsent(id, name);
            return id;
        }

        /** "Food / Cafe, Trip" is the category Cafe under Food and the tag Trip; the category comes first. */
        private List<String> tags(String categoryName) {
            List<String> ids = new ArrayList<>();
            if (categoryName.isBlank()) {
                return ids;
            }
            for (String part : categoryName.split(",\\s*")) {
                String[] levels = part.split("\\s*/\\s*", 2);
                String parent = levels[0].trim();
                if (parent.isEmpty()) {
                    continue;
                }
                String parentId = tag(parent, null);
                ids.add(levels.length > 1 && !levels[1].isBlank() ? tag(levels[1].trim(), parentId) : parentId);
            }
            return ids;
        }

        private String tag(String title, String parentId) {
            String key = (parentId == null ? "" : parentId + "/") + title;
            return tags.computeIfAbsent(
                            key,
                            k -> new ZenMoneyDump.Tag(
                                    "csv-tag-" + (tags.size() + 1), title, parentId, null, null, null))
                    .id();
        }

        ZenMoneyDump dump() {
            List<ZenMoneyDump.Instrument> instrumentList = new ArrayList<>();
            instruments.forEach((code, id) -> instrumentList.add(new ZenMoneyDump.Instrument(id, code)));
            List<ZenMoneyDump.Account> accountList = new ArrayList<>();
            accountTitles.forEach((id, title) -> accountList.add(new ZenMoneyDump.Account(
                    id, accountInstruments.get(id), title, null, null, null, null, null, null, null, null)));
            // The currency of most operations stands for the base currency
            Integer base = currencyUse.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(entry -> instruments.get(entry.getKey()))
                    .orElse(null);
            return new ZenMoneyDump(
                    null,
                    instrumentList,
                    List.of(new ZenMoneyDump.User(0, base)),
                    accountList,
                    List.copyOf(tags.values()),
                    List.of(),
                    transactions,
                    List.of());
        }
    }

    /** "1 234,56" or "1234.56"; empty is zero. */
    static BigDecimal amount(String text) {
        String clean = text.replace(" ", "").replace(" ", "");
        if (clean.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(clean.replace(',', '.')).abs();
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
    }

    private static String key(Row row) {
        return String.join(
                "|",
                row.get("date"),
                row.get("createdDate"),
                row.get("outcomeAccountName"),
                row.get("outcome"),
                row.get("incomeAccountName"),
                row.get("income"),
                row.get("categoryName"),
                row.get("payee"),
                row.get("comment"));
    }

    /** Stable for the same row, so that importing the same file again finds the operations already imported. */
    private static String id(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "csv-" + HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /** RFC 4180: commas separate fields, quotes enclose fields with commas, quotes or line breaks. */
    static List<List<String>> rows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
