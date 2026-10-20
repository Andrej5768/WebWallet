package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything of a user as one ZIP: {@code webwallet.json} with the rows of every table the user owns, and
 * {@code transactions.csv} for spreadsheets.
 *
 * <p>The tables are found in the database schema: those with a {@code user_id} column, and the tables that hang off
 * them through a foreign key (splits, tags of transactions, bank account links and the like). New tables are
 * exported without changes here. Sign-in data and secrets are left out.
 */
@Service
public class DataExportService {

    static final int FORMAT_VERSION = 1;

    /** Sign-in data, temporary data and the migration history. */
    private static final Set<String> SKIPPED_TABLES = Set.of(
            "users",
            "user_tokens",
            "refresh_tokens",
            "recovery_codes",
            "trusted_devices",
            "oauth_states",
            "import_previews",
            "flyway_schema_history");

    /** Encrypted bank tokens, webhook secrets, hashes. */
    private static final Pattern SECRET_COLUMN = Pattern.compile(".*(password|secret|token|hash).*");

    private static final String FOREIGN_KEYS = """
            select kcu.table_name as child, kcu.column_name as child_column,
                   ccu.table_name as parent, ccu.column_name as parent_column
            from information_schema.table_constraints tc
            join information_schema.key_column_usage kcu
              on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
            join information_schema.constraint_column_usage ccu
              on ccu.constraint_name = tc.constraint_name and ccu.table_schema = tc.table_schema
            where tc.constraint_type = 'FOREIGN KEY' and tc.table_schema = current_schema()
            order by kcu.table_name, kcu.column_name
            """;

    private static final String TRANSACTIONS_CSV = """
            select t.date, t.type, a.name as account, t.amount, a.currency,
                   ta.name as to_account, t.to_amount, ta.currency as to_currency,
                   t.original_amount, t.original_currency,
                   (select string_agg(coalesce(pc.name || ' / ', '') || c.name, '; ' order by s.id)
                      from transaction_splits s
                      join categories c on c.id = s.category_id
                      left join categories pc on pc.id = c.parent_id
                     where s.transaction_id = t.id) as category,
                   p.name as payee,
                   (select string_agg(tg.name, ', ' order by tg.name)
                      from transaction_tags tt join tags tg on tg.id = tt.tag_id
                     where tt.transaction_id = t.id) as tags,
                   t.comment
            from transactions t
            join accounts a on a.id = t.account_id
            left join accounts ta on ta.id = t.to_account_id
            left join payees p on p.id = t.payee_id
            where t.user_id = ?
            order by t.date, t.id
            """;

    private static final List<String> CSV_COLUMNS = List.of(
            "date",
            "type",
            "account",
            "amount",
            "currency",
            "to_account",
            "to_amount",
            "to_currency",
            "original_amount",
            "original_currency",
            "category",
            "payee",
            "tags",
            "comment");

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final UserRepository users;
    private final Clock clock;

    public DataExportService(JdbcTemplate jdbc, JsonMapper jsonMapper, UserRepository users, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.users = users;
        this.clock = clock;
    }

    /** The ZIP archive with the data of the user. */
    @Transactional(readOnly = true)
    public byte[] export(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("webwallet.json"));
            zip.write(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document(user)));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("transactions.csv"));
            Writer csv = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
            writeTransactions(userId, csv);
            csv.flush();
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    Map<String, Object> document(User user) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("email", user.getEmail());
        profile.put("firstName", user.getFirstName());
        profile.put("lastName", user.getLastName());
        profile.put("locale", user.getLocale());
        profile.put("baseCurrency", user.getBaseCurrency());
        profile.put("createdAt", user.getCreatedAt().toString());
        profile.put("twoFactorEnabled", user.isTwoFactorEnabled());
        profile.put("googleEmail", user.getGoogleEmail());

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "webwallet-export");
        document.put("version", FORMAT_VERSION);
        document.put("exportedAt", clock.instant().toString());
        document.put("user", profile);
        document.put("tables", tables(user.getId()));
        return document;
    }

    private Map<String, List<Map<String, Object>>> tables(Long userId) {
        List<String> owned = jdbc
                .queryForList(
                        "select table_name from information_schema.columns"
                                + " where table_schema = current_schema() and column_name = 'user_id'",
                        String.class)
                .stream()
                .filter(table -> !SKIPPED_TABLES.contains(table))
                .toList();
        Map<String, List<Map<String, Object>>> tables = new TreeMap<>();
        for (String table : owned) {
            tables.put(table, rows("select * from " + quote(table) + " where user_id = ? order by 1", userId));
        }
        // Tables without user_id that belong to an owned row; one foreign key per table is enough
        for (Map<String, Object> key : jdbc.queryForList(FOREIGN_KEYS)) {
            String child = (String) key.get("child");
            String parent = (String) key.get("parent");
            if (tables.containsKey(child) || SKIPPED_TABLES.contains(child) || !owned.contains(parent)) {
                continue;
            }
            tables.put(
                    child,
                    rows(
                            "select * from " + quote(child) + " where " + quote((String) key.get("child_column"))
                                    + " in (select " + quote((String) key.get("parent_column")) + " from "
                                    + quote(parent) + " where user_id = ?) order by 1",
                            userId));
        }
        return tables;
    }

    private List<Map<String, Object>> rows(String sql, Long userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, userId)) {
            Map<String, Object> clean = new LinkedHashMap<>();
            row.forEach((column, value) -> {
                if (!column.equals("user_id") && !SECRET_COLUMN.matcher(column).matches()) {
                    clean.put(column, plain(value));
                }
            });
            rows.add(clean);
        }
        return rows;
    }

    /** JDBC values as JSON-friendly ones: times as ISO text, other database types (uuid, json) as their text. */
    private static Object plain(Object value) {
        try {
            return switch (value) {
                case null -> null;
                case Timestamp timestamp -> timestamp.toInstant().toString();
                case java.sql.Date date -> date.toLocalDate().toString();
                case java.sql.Time time -> time.toLocalTime().toString();
                case Array array ->
                    Arrays.stream((Object[]) array.getArray())
                            .map(DataExportService::plain)
                            .toList();
                case Number number -> number;
                case Boolean flag -> flag;
                case String text -> text;
                default -> value.toString();
            };
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read an array value", e);
        }
    }

    private void writeTransactions(Long userId, Writer out) throws IOException {
        // The byte order mark makes Excel read the file as UTF-8
        out.write('﻿');
        out.write(String.join(",", CSV_COLUMNS));
        out.write("\r\n");
        for (Map<String, Object> row : jdbc.queryForList(TRANSACTIONS_CSV, userId)) {
            List<String> cells = new ArrayList<>();
            for (String column : CSV_COLUMNS) {
                Object value = plain(row.get(column));
                cells.add(csvCell(value == null ? "" : value.toString()));
            }
            out.write(String.join(",", cells));
            out.write("\r\n");
        }
    }

    static String csvCell(String value) {
        boolean quote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
