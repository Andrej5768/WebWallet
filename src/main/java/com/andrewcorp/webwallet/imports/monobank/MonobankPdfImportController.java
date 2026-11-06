package com.andrewcorp.webwallet.imports.monobank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.imports.ImportDetector;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewDtos.ImportPreviewResponse;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewService;
import com.andrewcorp.webwallet.imports.privatbank.PdfText;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Imports")
@SecurityRequirement(name = "bearer")
@RestController
public class MonobankPdfImportController {

    private final PdfText pdfText;
    private final MonobankPdfParser parser;
    private final ImportPreviewService previews;
    private final ImportDetector detector;

    public MonobankPdfImportController(
            PdfText pdfText, MonobankPdfParser parser, ImportPreviewService previews, ImportDetector detector) {
        this.pdfText = pdfText;
        this.parser = parser;
        this.previews = previews;
        this.detector = detector;
    }

    /** What a file shared into the app is: the source to preview it with. */
    public record ImportDetectResponse(ImportDetector.DetectedSource source) {}

    @Operation(
            summary = "Preview a Monobank card statement (signed PDF from the Monobank app) without saving anything",
            description = "Without accountId the account with the card number of the statement is used, e.g. the one"
                    + " of a Monobank connection, or created. Operations already brought in through the connection are"
                    + " shown as duplicates. Confirm with POST /api/v1/imports/previews/{id}/confirm within an hour;"
                    + " the file is not stored.")
    @PostMapping(path = "/api/v1/imports/monobank/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreviewResponse preview(
            @CurrentUserId Long userId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Long accountId) {
        return previews.previewMonobank(userId, accountId, parser.parse(pdfText.extract(bytes(file))));
    }

    @Operation(
            summary = "Tell which import a file belongs to",
            description = "For a file shared into the app: a PrivatBank or Monobank statement by the bank named in the"
                    + " PDF, a Zen Money export by its header, a spreadsheet as Freedom Finance reports; UNKNOWN"
                    + " otherwise. The file is not stored.")
    @PostMapping(path = "/api/v1/imports/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportDetectResponse detect(@CurrentUserId Long userId, @RequestPart("file") MultipartFile file) {
        return new ImportDetectResponse(detector.detect(bytes(file)));
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
    }
}
