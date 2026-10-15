package com.andrewcorp.webwallet.imports.preview;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.imports.ImportResult;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewDtos.ConfirmRequest;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewDtos.ImportPreviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Imports")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/imports/previews")
public class ImportPreviewController {

    private final ImportPreviewService previews;

    public ImportPreviewController(ImportPreviewService previews) {
        this.previews = previews;
    }

    @Operation(summary = "A page of the operations of an import preview, newest first; 404 when it expired")
    @GetMapping("/{id}")
    public ImportPreviewResponse get(
            @CurrentUserId Long userId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + ImportPreviewService.DEFAULT_PAGE_SIZE)
                    @Min(1) @Max(ImportPreviewService.MAX_PAGE_SIZE) int size) {
        return previews.get(userId, id, page, size);
    }

    @Operation(
            summary = "Import the previewed operations with the changes of the user",
            description = "Operations not listed are imported as previewed. The preview is removed afterwards;"
                    + " 404 when it is unknown or expired.")
    @PostMapping("/{id}/confirm")
    public ImportResult confirm(
            @CurrentUserId Long userId, @PathVariable UUID id, @Valid @RequestBody ConfirmRequest request) {
        return previews.confirm(userId, id, request);
    }

    @Operation(summary = "Discard an import preview")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@CurrentUserId Long userId, @PathVariable UUID id) {
        previews.discard(userId, id);
    }
}
