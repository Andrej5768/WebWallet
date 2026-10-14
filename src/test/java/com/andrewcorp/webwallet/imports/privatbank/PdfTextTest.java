package com.andrewcorp.webwallet.imports.privatbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PdfTextTest {

    private static byte[] pdf;
    private final PdfText pdfText = new PdfText();

    @BeforeAll
    static void createPdf() throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : new String[] {"Statement page one", "Statement page two"}) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(line);
                    content.endText();
                }
            }
            document.save(out);
            pdf = out.toByteArray();
        }
    }

    @Test
    void plainPdf() {
        assertThat(pdfText.extract(pdf)).contains("Statement page one\n").contains("Statement page two");
    }

    @Test
    void pdfWrappedInSignedContainer() throws Exception {
        // Privat24 statements come as a CMS / PKCS#7 container with the PDF inside
        byte[] signed = new CMSSignedDataGenerator()
                .generate(new CMSProcessableByteArray(pdf), true)
                .getEncoded();
        assertThat(signed[0]).isEqualTo((byte) 0x30);

        assertThat(pdfText.extract(signed)).contains("Statement page one");
    }

    @Test
    void pdfInsideUnknownBytes() {
        byte[] prefix = {0x30, (byte) 0x83, 0x01, 0x02};
        byte[] suffix = "trailing signature".getBytes(StandardCharsets.US_ASCII);
        byte[] wrapped = new byte[prefix.length + pdf.length + suffix.length];
        System.arraycopy(prefix, 0, wrapped, 0, prefix.length);
        System.arraycopy(pdf, 0, wrapped, prefix.length, pdf.length);
        System.arraycopy(suffix, 0, wrapped, prefix.length + pdf.length, suffix.length);

        assertThat(pdfText.extract(wrapped)).contains("Statement page two");
    }

    @Test
    void notAPdf() {
        assertThatThrownBy(() -> pdfText.extract("hello".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_IMPORT_FILE));
        assertThatThrownBy(() -> pdfText.extract("%PDF-1.7 broken %%EOF".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(ApiException.class);
    }
}
