package com.andrewcorp.webwallet.imports.zenmoney;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.imports.ImportResult;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewDtos.ImportPreviewResponse;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Tag(name = "Imports")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/imports/zenmoney")
public class ZenMoneyImportController {

    private final ZenMoneyClient client;
    private final ZenMoneyImporter importer;
    private final ImportPreviewService previews;
    private final JsonMapper jsonMapper;

    public ZenMoneyImportController(
            ZenMoneyClient client, ZenMoneyImporter importer, ImportPreviewService previews, JsonMapper jsonMapper) {
        this.client = client;
        this.importer = importer;
        this.previews = previews;
        this.jsonMapper = jsonMapper;
    }

    @Operation(summary = "Import everything from Zen Money with an API token; the token is not stored")
    @PostMapping
    public ImportResult importWithToken(@CurrentUserId Long userId, @Valid @RequestBody TokenRequest request) {
        return importer.importDump(userId, client.fetchAll(request.token()));
    }

    @Operation(
            summary = "Import a file of Zen Money",
            description = "The CSV export of the app (dump of transactions) or a saved response of the sync API"
                    + " (POST /v8/diff/ with serverTimestamp 0)")
    @PostMapping(path = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importFile(@CurrentUserId Long userId, @RequestPart("file") MultipartFile file) {
        return importer.importDump(userId, parse(file));
    }

    @Operation(
            summary = "Preview everything from Zen Money with an API token without saving anything",
            description = "Returns a summary (accounts, categories and payees to create, budgets) and the first page"
                    + " of the transactions; review them and import with POST /api/v1/imports/previews/{id}/confirm"
                    + " within an hour. The token is not stored.")
    @PostMapping("/preview")
    public ImportPreviewResponse previewWithToken(
            @CurrentUserId Long userId, @Valid @RequestBody TokenRequest request) {
        return previews.previewZenMoney(userId, client.fetchAll(request.token()));
    }

    @Operation(
            summary = "Preview a file of Zen Money without saving anything",
            description = "Like POST /api/v1/imports/zenmoney/preview; only the parsed records are kept, not the file.")
    @PostMapping(path = "/file/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreviewResponse previewFile(@CurrentUserId Long userId, @RequestPart("file") MultipartFile file) {
        return previews.previewZenMoney(userId, parse(file));
    }

    /** The CSV export of the app, or a saved response of the sync API (JSON). */
    private ZenMoneyDump parse(MultipartFile file) {
        ZenMoneyDump dump;
        try {
            byte[] content = file.getBytes();
            dump = ZenMoneyCsv.looksLikeCsv(content)
                    ? ZenMoneyCsv.parse(content)
                    : jsonMapper.readValue(content, ZenMoneyDump.class);
        } catch (IOException | JacksonException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        if (dump == null || dump.account() == null) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        return dump;
    }

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {}
}
