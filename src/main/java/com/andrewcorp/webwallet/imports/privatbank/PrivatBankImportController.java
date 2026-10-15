package com.andrewcorp.webwallet.imports.privatbank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.imports.ImportResult;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewDtos.ImportPreviewResponse;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Imports")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/imports/privatbank")
public class PrivatBankImportController {

    private final PdfText pdfText;
    private final PrivatStatementParser parser;
    private final PrivatBankImporter importer;
    private final ImportPreviewService previews;

    public PrivatBankImportController(
            PdfText pdfText, PrivatStatementParser parser, PrivatBankImporter importer, ImportPreviewService previews) {
        this.pdfText = pdfText;
        this.parser = parser;
        this.importer = importer;
        this.previews = previews;
    }

    @Operation(
            summary = "Import a PrivatBank card statement (PDF from Privat24); the file is not stored. Without "
                    + "accountId the account with the card number of the statement is used or created.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importStatement(
            @CurrentUserId Long userId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Long accountId) {
        return importer.importStatement(userId, accountId, parse(file));
    }

    @Operation(
            summary = "Preview a PrivatBank card statement without saving anything",
            description = "Returns the operations as they would be imported and the first page of them; review them"
                    + " and import with POST /api/v1/imports/previews/{id}/confirm within an hour. Only the parsed"
                    + " operations are kept, not the file.")
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreviewResponse preview(
            @CurrentUserId Long userId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Long accountId) {
        return previews.previewPrivatBank(userId, accountId, parse(file));
    }

    private PrivatStatement parse(MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        return parser.parse(pdfText.extract(bytes));
    }
}
