package com.andrewcorp.webwallet.imports;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the first sheet of an .xlsx file as rows of cell texts: shared and inline strings as written, numbers and
 * dates as Excel stores them (a date is a serial number, see {@link #dateTime}). Enough for the reports of brokers;
 * formulas, styles and other sheets are ignored.
 */
public final class XlsxReader {

    /** Unpacked size allowed per part of the file, against zip bombs. */
    private static final long MAX_PART_BYTES = 50L * 1024 * 1024;
    /** A workbook has a few dozen parts; many more is not a report of a broker. */
    private static final int MAX_ENTRIES = 1000;

    private static final String SHARED_STRINGS = "xl/sharedStrings.xml";
    private static final String FIRST_SHEET = "xl/worksheets/sheet1.xml";
    private static final String SHEETS = "xl/worksheets/";

    /** Excel counts days from 30 December 1899 (with the 1900 leap year bug, which this start absorbs). */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1899, 12, 30, 0, 0);

    private static final XMLInputFactory XML = XMLInputFactory.newFactory();

    static {
        XML.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    private XlsxReader() {}

    /**
     * The rows of the first sheet; a missing cell is null, a row ends with its last cell.
     *
     * @throws ApiException {@link ErrorCode#INVALID_IMPORT_FILE} when the file is not a readable .xlsx
     */
    public static List<List<String>> firstSheet(byte[] file) {
        // Only the shared strings and one sheet are unpacked: a file of many large sheets must not fill the memory
        byte[] strings = null;
        byte[] sheet = null;
        boolean firstSheetFound = false;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > MAX_ENTRIES) {
                    throw invalid();
                }
                String name = entry.getName();
                if (name.equals(SHARED_STRINGS) && strings == null) {
                    strings = read(zip);
                } else if (name.equals(FIRST_SHEET)) {
                    sheet = read(zip);
                    firstSheetFound = true;
                } else if (!firstSheetFound && sheet == null && name.startsWith(SHEETS) && name.endsWith(".xml")) {
                    // Another sheet stands for the first one when the file has no sheet1.xml
                    sheet = read(zip);
                }
            }
        } catch (IOException e) {
            throw invalid();
        }
        if (sheet == null) {
            throw invalid();
        }
        try {
            List<String> sharedStrings = strings != null ? sharedStrings(strings) : List.of();
            return rows(sheet, sharedStrings);
        } catch (XMLStreamException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw invalid();
        }
    }

    /** The date and time of an Excel serial number, to the second. */
    public static LocalDateTime dateTime(String serial) {
        double days = Double.parseDouble(serial);
        long seconds = Math.round(days * 86_400);
        return EPOCH.plus(seconds, ChronoUnit.SECONDS);
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
            total += n;
            if (total > MAX_PART_BYTES) {
                throw invalid();
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        List<String> strings = new ArrayList<>();
        XMLStreamReader reader = XML.createXMLStreamReader(new ByteArrayInputStream(xml));
        StringBuilder current = null;
        boolean text = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "si" -> current = new StringBuilder();
                    case "t" -> text = current != null;
                    // Phonetic hints are not part of the text
                    case "rPh" -> text = false;
                    default -> {}
                }
            } else if (event == XMLStreamConstants.CHARACTERS && text) {
                current.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "t" -> text = false;
                    case "si" -> {
                        strings.add(current.toString());
                        current = null;
                    }
                    default -> {}
                }
            }
        }
        return strings;
    }

    private static List<List<String>> rows(byte[] xml, List<String> strings) throws XMLStreamException {
        List<List<String>> rows = new ArrayList<>();
        XMLStreamReader reader = XML.createXMLStreamReader(new ByteArrayInputStream(xml));
        List<String> row = null;
        int column = 0;
        String type = null;
        StringBuilder value = null;
        boolean collecting = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "row" -> {
                        row = new ArrayList<>();
                        column = 0;
                    }
                    case "c" -> {
                        String ref = reader.getAttributeValue(null, "r");
                        column = ref != null ? column(ref) : (row != null ? row.size() : 0);
                        type = reader.getAttributeValue(null, "t");
                        value = new StringBuilder();
                    }
                    case "v", "t" -> collecting = value != null;
                    default -> {}
                }
            } else if (event == XMLStreamConstants.CHARACTERS && collecting) {
                value.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "v", "t" -> collecting = false;
                    case "c" -> {
                        if (row != null) {
                            while (row.size() < column) {
                                row.add(null);
                            }
                            row.add(cell(type, value.toString(), strings));
                        }
                        value = null;
                    }
                    case "row" -> {
                        rows.add(row);
                        row = null;
                    }
                    default -> {}
                }
            }
        }
        return rows;
    }

    private static String cell(String type, String value, List<String> strings) {
        if ("s".equals(type)) {
            int index = Integer.parseInt(value.trim());
            if (index < 0 || index >= strings.size()) {
                throw invalid();
            }
            return strings.get(index);
        }
        return value.isEmpty() ? null : value;
    }

    /** The 0-based column of a cell reference such as {@code AB12}. */
    private static int column(String ref) {
        int column = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
            column = column * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        }
        return Math.max(0, column - 1);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_IMPORT_FILE);
    }
}
