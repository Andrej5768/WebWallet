package com.andrewcorp.webwallet.imports.freedom;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.BrokerConfirmRequest;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.BrokerImportResult;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.BrokerPreviewResponse;
import com.andrewcorp.webwallet.imports.broker.BrokerImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Imports")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/imports/freedom")
public class FreedomImportController {

    /** The trades report and the cash flows report. */
    private static final int MAX_FILES = 2;

    private final FreedomReportParser parser;
    private final BrokerImportService imports;

    public FreedomImportController(FreedomReportParser parser, BrokerImportService imports) {
        this.parser = parser;
        this.imports = imports;
    }

    @Operation(
            summary = "Preview Freedom Finance reports (XLSX) without saving anything",
            description = "The trades report and the cash flows report, in any order; the cash flows give the"
                    + " currency of the fees, so both are needed for exact money. Without accountId the operations go"
                    + " to the brokerage account named Freedom Finance, created when there is none. Confirm with"
                    + " POST /previews/{id}/confirm within an hour, discard with DELETE /api/v1/imports/previews/{id}."
                    + " Only the parsed reports are kept, not the files.")
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BrokerPreviewResponse preview(
            @CurrentUserId Long userId,
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(required = false) Long accountId) {
        if (files.isEmpty() || files.size() > MAX_FILES) {
            throw ApiException.invalid("files", "error.import.brokerFile");
        }
        List<byte[]> contents = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                contents.add(file.getBytes());
            } catch (IOException e) {
                throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
            }
        }
        return imports.previewFreedom(userId, accountId, parser.parse(contents));
    }

    @Operation(
            summary = "Import the previewed reports",
            description = "Tickers not listed get the suggested instruments, operations not excluded are imported;"
                    + " those imported before are skipped. Rejected when the reports sell more than they buy before."
                    + " The preview is removed afterwards; 404 when it is unknown or expired.")
    @PostMapping("/previews/{id}/confirm")
    public BrokerImportResult confirm(
            @CurrentUserId Long userId, @PathVariable UUID id, @Valid @RequestBody BrokerConfirmRequest request) {
        return imports.confirmFreedom(userId, id, request);
    }
}
