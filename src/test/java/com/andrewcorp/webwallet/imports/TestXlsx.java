package com.andrewcorp.webwallet.imports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a minimal .xlsx as spreadsheet programs write it: strings in the shared strings, numbers in the cells, dates
 * as serial numbers.
 */
public final class TestXlsx {

    private TestXlsx() {}

    public static byte[] of(List<List<Object>> rows) {
        List<String> strings = new ArrayList<>();
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            List<Object> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                Object value = row.get(c);
                if (value == null) {
                    continue;
                }
                String ref = (char) ('A' + c) + String.valueOf(r + 1);
                if (value instanceof String text) {
                    sheet.append("<c r=\"")
                            .append(ref)
                            .append("\" t=\"s\"><v>")
                            .append(strings.size())
                            .append("</v></c>");
                    strings.add(text);
                } else if (value instanceof LocalDateTime time) {
                    double days = Duration.between(LocalDateTime.of(1899, 12, 30, 0, 0), time)
                                    .getSeconds()
                            / 86_400.0;
                    sheet.append("<c r=\"")
                            .append(ref)
                            .append("\" s=\"2\"><v>")
                            .append(days)
                            .append("</v></c>");
                } else {
                    sheet.append("<c r=\"")
                            .append(ref)
                            .append("\"><v>")
                            .append(value)
                            .append("</v></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");

        StringBuilder shared = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        for (String text : strings) {
            shared.append("<si><t xml:space=\"preserve\">").append(escape(text)).append("</t></si>");
        }
        shared.append("</sst>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "xl/sharedStrings.xml", shared.toString());
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
