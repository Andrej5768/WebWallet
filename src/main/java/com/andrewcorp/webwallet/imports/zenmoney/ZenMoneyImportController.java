package com.andrewcorp.webwallet.imports.zenmoney;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.imports.ImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

@Tag(name = "Imports")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/imports/zenmoney")
public class ZenMoneyImportController {

    private final ZenMoneyClient client;
    private final ZenMoneyImporter importer;
    private final JsonMapper jsonMapper;

    public ZenMoneyImportController(ZenMoneyClient client, ZenMoneyImporter importer, JsonMapper jsonMapper) {
        this.client = client;
        this.importer = importer;
        this.jsonMapper = jsonMapper;
    }

    @Operation(summary = "Import everything from Zen Money with an API token; the token is not stored")
    @PostMapping
    public ImportResult importWithToken(@CurrentUserId Long userId, @Valid @RequestBody TokenRequest request) {
        return importer.importDump(userId, client.fetchAll(request.token()));
    }

    @Operation(summary = "Import a saved response of the Zen Money sync API (POST /v8/diff/ with serverTimestamp 0)")
    @PostMapping(path = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importFile(@CurrentUserId Long userId, @RequestPart("file") MultipartFile file) {
        ZenMoneyDump dump;
        try (InputStream input = file.getInputStream()) {
            dump = jsonMapper.readValue(input, ZenMoneyDump.class);
        } catch (IOException | JacksonException e) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        if (dump == null || dump.account() == null) {
            throw new ApiException(ErrorCode.INVALID_IMPORT_FILE);
        }
        return importer.importDump(userId, dump);
    }

    public record TokenRequest(@NotBlank String token) {
    }
}
