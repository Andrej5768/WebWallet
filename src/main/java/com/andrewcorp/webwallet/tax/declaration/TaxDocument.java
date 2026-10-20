package com.andrewcorp.webwallet.tax.declaration;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * A document of the electronic reporting of the State Tax Service: windows-1251, the root {@code DECLAR} with its
 * schema, the head {@code DECLARHEAD} and the body {@code DECLARBODY}, whose elements are written in the order of the
 * schema by the caller. Money has two decimal places, a checkbox is {@code 1}, rows of a table carry {@code ROWNUM}.
 */
final class TaxDocument {

    static final String ENCODING = "windows-1251";
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("ddMMyyyy");

    /**
     * A form of the tax service.
     *
     * @param doc     e.g. F01
     * @param sub     e.g. 033
     * @param version e.g. 9
     */
    record Form(String doc, String sub, int version) {

        String code() {
            return doc + sub + String.format("%02d", version);
        }
    }

    /** The document a document is linked with: an appendix (type 1) or the main declaration (type 2). */
    record Link(Form form, String fileName, int type) {}

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final XMLStreamWriter writer;

    private TaxDocument(Form form) {
        try {
            writer = XMLOutputFactory.newFactory().createXMLStreamWriter(out, ENCODING);
            writer.writeStartDocument(ENCODING, "1.0");
            writer.writeStartElement("DECLAR");
            writer.writeNamespace("xsi", XSI);
            writer.writeAttribute(XSI, "noNamespaceSchemaLocation", form.code() + ".xsd");
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Starts a document with its head: a reporting (not a clarifying) document, the first of its kind in the period.
     *
     * @param periodType 1 month, 2 quarter, 3 half-year, 4 nine months, 5 year
     */
    static TaxDocument start(
            Form form,
            TaxProfileData profile,
            int periodType,
            int periodMonth,
            int year,
            List<Link> links,
            LocalDate filled) {
        TaxDocument document = new TaxDocument(form);
        document.open("DECLARHEAD");
        document.text("TIN", profile.tin());
        document.text("C_DOC", form.doc());
        document.text("C_DOC_SUB", form.sub());
        document.text("C_DOC_VER", String.valueOf(form.version()));
        document.text("C_DOC_TYPE", "0");
        document.text("C_DOC_CNT", "1");
        document.text("C_REG", String.valueOf(profile.regionCode()));
        document.text("C_RAJ", String.valueOf(profile.districtCode()));
        document.text("PERIOD_MONTH", String.valueOf(periodMonth));
        document.text("PERIOD_TYPE", String.valueOf(periodType));
        document.text("PERIOD_YEAR", String.valueOf(year));
        document.text("C_STI_ORIG", String.valueOf(profile.taxOfficeCode()));
        document.text("C_DOC_STAN", "1");
        if (!links.isEmpty()) {
            document.open("LINKED_DOCS");
            int number = 1;
            for (Link link : links) {
                document.open("DOC");
                document.attribute("NUM", String.valueOf(number++));
                document.attribute("TYPE", String.valueOf(link.type()));
                document.text("C_DOC", link.form().doc());
                document.text("C_DOC_SUB", link.form().sub());
                document.text("C_DOC_VER", String.valueOf(link.form().version()));
                document.text("C_DOC_TYPE", "0");
                document.text("C_DOC_CNT", "1");
                document.text("C_DOC_STAN", "1");
                document.text("FILENAME", link.fileName());
                document.close();
            }
            document.close();
        }
        document.text("D_FILL", filled.format(DATE));
        document.text("SOFTWARE", "WebWallet");
        document.close();
        document.open("DECLARBODY");
        return document;
    }

    /**
     * The name the tax service expects: the office (region and district), the tax number padded to ten digits, the
     * form, the state (reporting), the number of the document of its kind, the period and the office again.
     */
    static String fileName(Form form, TaxProfileData profile, int periodType, int periodMonth, int year) {
        String tin = profile.tin().matches("[0-9]+")
                ? String.format("%10s", profile.tin()).replace(' ', '0')
                : "00" + profile.tin();
        return String.format("%02d%02d", profile.regionCode(), profile.districtCode())
                + tin
                + form.doc()
                + form.sub()
                + String.format("%02d", form.version())
                + "1"
                + "00"
                + "0000001"
                + periodType
                + String.format("%02d", periodMonth)
                + year
                + String.format("%04d", profile.taxOfficeCode())
                + ".xml";
    }

    static String date(LocalDate date) {
        return date.format(DATE);
    }

    TaxDocument text(String name, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        try {
            writer.writeStartElement(name);
            writer.writeCharacters(value.trim());
            writer.writeEndElement();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    TaxDocument check(String name) {
        return text(name, "1");
    }

    TaxDocument money(String name, BigDecimal value) {
        return value == null
                ? this
                : text(name, value.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    TaxDocument row(String name, int number, String value) {
        if (value == null) {
            return this;
        }
        try {
            writer.writeStartElement(name);
            writer.writeAttribute("ROWNUM", String.valueOf(number));
            writer.writeCharacters(value);
            writer.writeEndElement();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    TaxDocument moneyRow(String name, int number, BigDecimal value) {
        return row(name, number, value.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /** Ends the body and the document. */
    byte[] finish() {
        try {
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private void open(String name) {
        try {
            writer.writeStartElement(name);
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    private void attribute(String name, String value) {
        try {
            writer.writeAttribute(name, value);
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    private void close() {
        try {
            writer.writeEndElement();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }
}
