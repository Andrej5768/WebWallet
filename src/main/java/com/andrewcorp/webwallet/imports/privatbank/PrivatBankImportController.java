package com.andrewcorp.webwallet.imports.privatbank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.imports.ImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Tag(name = "Imports")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/imports/privatbank")
public class PrivatBankImportController {

    private final PdfText pdfText;
    private final PrivatStatementParser parser;
    private final PrivatBankImporter importer;

    public PrivatBankImportController(PdfText pdfText, PrivatStatementParser parser, PrivatBankImporter importer) {
        this.pdfText = pdfText;
        this.parser = parser;
        this.importer = importer;
    }

    @Operation(summary = "Import a PrivatBank card statement (PDF from Privat24); the file is not stored. Without "
            + "accountId the account with the card number of the statement is used or created.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importStatement(@CurrentUserId Long userId, @RequestPart("file") MultipartFile file,
                                        @RequestParam(required = false) Long accountId) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        return importer.importStatement(userId, accountId, parser.parse(pdfText.extract(bytes)));
    }
}
