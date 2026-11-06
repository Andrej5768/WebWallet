package com.andrewcorp.webwallet.imports;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.imports.monobank.MonobankPdfParser;
import com.andrewcorp.webwallet.imports.privatbank.PdfText;
import com.andrewcorp.webwallet.imports.zenmoney.ZenMoneyCsv;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Tells which import a file belongs to, for a file shared into the app from another one: the phone knows only that
 * it is a PDF or a spreadsheet. PDFs are told apart by the bank named in the text.
 */
@Component
public class ImportDetector {

    /** What a file can be imported as; UNKNOWN lets the user choose. */
    public enum DetectedSource {
        PRIVATBANK,
        MONOBANK,
        ZENMONEY,
        FREEDOM,
        UNKNOWN
    }

    private static final String PRIVATBANK_MARKER = "приватбанк";

    private final PdfText pdfText;

    public ImportDetector(PdfText pdfText) {
        this.pdfText = pdfText;
    }

    public DetectedSource detect(byte[] file) {
        if (file.length >= 2 && file[0] == 'P' && file[1] == 'K') {
            // An XLSX is a ZIP archive; the broker reports are the only spreadsheets imported
            return DetectedSource.FREEDOM;
        }
        if (ZenMoneyCsv.looksLikeCsv(file)) {
            return DetectedSource.ZENMONEY;
        }
        String text;
        try {
            text = pdfText.extract(file);
        } catch (ApiException e) {
            return DetectedSource.UNKNOWN;
        }
        if (text.contains(MonobankPdfParser.BANK_MARKER)) {
            return DetectedSource.MONOBANK;
        }
        if (text.toLowerCase(Locale.ROOT).contains(PRIVATBANK_MARKER)) {
            return DetectedSource.PRIVATBANK;
        }
        return DetectedSource.UNKNOWN;
    }
}
