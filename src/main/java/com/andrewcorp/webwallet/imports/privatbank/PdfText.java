package com.andrewcorp.webwallet.imports.privatbank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSTypedData;
import org.springframework.stereotype.Component;

/**
 * Extracts the text of a PDF. Bank statements are often wrapped in a CMS / PKCS#7 signature container, which is
 * unwrapped first.
 */
@Component
public class PdfText {

    private static final byte[] PDF_START = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDF_END = "%%EOF".getBytes(StandardCharsets.US_ASCII);

    /** Text of all pages with {@code \n} line breaks. */
    public String extract(byte[] file) {
        try (PDDocument document = Loader.loadPDF(unwrap(file))) {
            PDFTextStripper stripper = new PDFTextStripper();
            // The defaults are the system line separator, \r\n on Windows
            stripper.setLineSeparator("\n");
            stripper.setPageEnd("\n");
            return stripper.getText(document);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
    }

    /** Returns the PDF inside a signed container, or the bytes from the first {@code %PDF-} to the last {@code %%EOF}. */
    static byte[] unwrap(byte[] file) {
        if (startsWith(file, PDF_START)) {
            return file;
        }
        try {
            CMSTypedData content = new CMSSignedData(file).getSignedContent();
            if (content != null && content.getContent() instanceof byte[] pdf && startsWith(pdf, PDF_START)) {
                return pdf;
            }
        } catch (CMSException | RuntimeException e) {
            // Not a valid container: fall back to looking for the PDF inside the bytes
        }
        int start = indexOf(file, PDF_START, 0);
        int end = lastIndexOf(file, PDF_END);
        if (start < 0 || end < start) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        return Arrays.copyOfRange(file, start, end + PDF_END.length);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        for (int i = from; i <= data.length - pattern.length; i++) {
            if (Arrays.equals(data, i, i + pattern.length, pattern, 0, pattern.length)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] data, byte[] pattern) {
        for (int i = data.length - pattern.length; i >= 0; i--) {
            if (Arrays.equals(data, i, i + pattern.length, pattern, 0, pattern.length)) {
                return i;
            }
        }
        return -1;
    }
}
