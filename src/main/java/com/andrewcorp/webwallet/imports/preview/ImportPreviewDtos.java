package com.andrewcorp.webwallet.imports.preview;

import com.andrewcorp.webwallet.common.web.PageResponse;
import com.andrewcorp.webwallet.imports.ImportOperation;
import com.andrewcorp.webwallet.imports.ImportResult;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ImportPreviewDtos {

    private ImportPreviewDtos() {}

    /**
     * A parsed import that is not saved yet.
     *
     * @param id         to page through the operations and to confirm the import; valid until {@code expiresAt}
     * @param summary    what importing every operation as previewed would do: accounts, categories and payees
     *                   created, transactions imported, duplicates skipped or matched, budgets, and warnings
     * @param operations the operations, newest first
     */
    public record ImportPreviewResponse(
            UUID id,
            ImportSource source,
            Instant expiresAt,
            ImportResult summary,
            PageResponse<ImportOperation> operations) {}

    /** @param operations changes to some operations; the others are imported as previewed */
    public record ConfirmRequest(@Size(max = 100_000) List<@Valid OperationEdit> operations) {}

    /**
     * The reviewed state of an operation: the given values replace the suggested ones, so a client sends back the
     * category, tags and comment it shows. Changes to a duplicate have no effect: it is not imported.
     *
     * @param key                   key of the operation in the preview
     * @param include               false leaves the operation out of the import; true by default
     * @param type                  null keeps the detected type. Money leaving an account may be an EXPENSE or a
     *                              TRANSFER to {@code transferAccountId}, money arriving an INCOME or a TRANSFER from
     *                              it; a TRANSFER may become an EXPENSE of its source or an INCOME of its target
     *                              account
     * @param transferAccountId     the other own account, in the same currency, when an expense or income becomes a
     *                              transfer
     * @param categoryId            null for none; an income may have an expense category (a refund)
     * @param keepSuggestedCategory true keeps the suggested category of the preview, also one the import creates
     *                              ({@code categoryId} null and {@code categoryName} set in the preview), and ignores
     *                              {@code categoryId}; false by default. A transfer has no category
     * @param tagIds                null or empty for none
     * @param comment               null for none
     * @param rememberRule          also create a categorization rule: later operations of the payee get the category,
     *                              including the other operations of this import without changes. Ignored without a
     *                              payee or a category, and for a kept category that the import creates
     * @param pairKey               merge this expense or income with the given operation of the import into one
     *                              transfer, e.g. from its {@code transferSuggestions}; the type stays as detected
     * @param pairTransactionId     merge it with the given expense or income of the ledger into one transfer instead
     */
    public record OperationEdit(
            @NotBlank @Size(max = 300) String key,
            Boolean include,
            TransactionType type,
            Long transferAccountId,
            @Schema(nullable = true) Long categoryId,
            Boolean keepSuggestedCategory,
            @Schema(nullable = true) List<Long> tagIds,
            @Schema(nullable = true) @Size(max = 1000) String comment,
            Boolean rememberRule,
            @Schema(nullable = true) @Size(max = 300) String pairKey,
            @Schema(nullable = true) Long pairTransactionId) {}
}
