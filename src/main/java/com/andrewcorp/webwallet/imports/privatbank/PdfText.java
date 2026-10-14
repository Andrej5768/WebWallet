package com.andrewcorp.webwallet.imports.privatbank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSTypedData;
import org.springframework.stereotype.Component;

/**
 * Extracts the text of a PDF. Bank statements are often wrapped in a CMS / PKCS#7 signature container, which is
 * unwrapped first. Uploaded files are untrusted, so the memory, the page count and the text length are limited.
 */
@Component
public class PdfText {

    private static final byte[] PDF_START = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDF_END = "%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final long MAX_MEMORY_BYTES = 64L * 1024 * 1024;

    private final int maxPages;
    private final int maxTextLength;

    public PdfText() {
        // A statement for a few years has a few hundred pages of about 3000 characters
        this(1000, 5_000_000);
    }

    PdfText(int maxPages, int maxTextLength) {
        this.maxPages = maxPages;
        this.maxTextLength = maxTextLength;
    }

    /** Text of all pages with {@code \n} line breaks. */
    public String extract(byte[] file) {
        try (PDDocument document = Loader.loadPDF(
                unwrap(file), null, null, null, MemoryUsageSetting.setupMainMemoryOnly(MAX_MEMORY_BYTES).streamCache)) {
            if (document.getNumberOfPages() > maxPages) {
                throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            // The defaults are the system line separator, \r\n on Windows
            stripper.setLineSeparator("\n");
            stripper.setPageEnd("\n");
            // Written into a bounded buffer: a small PDF can expand into an enormous text
            LimitedWriter text = new LimitedWriter(maxTextLength);
            stripper.writeText(document, text);
            return text.toString();
        } catch (TextTooLongException | IOException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
    }

    private static final class TextTooLongException extends RuntimeException {

        TextTooLongException() {
            super(null, null, false, false);
        }
    }

    /** Collects the text and stops the extraction once it exceeds the limit. */
    private static final class LimitedWriter extends Writer {

        private final StringBuilder text = new StringBuilder();
        private final int maxLength;

        LimitedWriter(int maxLength) {
            this.maxLength = maxLength;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            if (text.length() + length > maxLength) {
                throw new TextTooLongException();
            }
            text.append(buffer, offset, length);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public String toString() {
            return text.toString();
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
