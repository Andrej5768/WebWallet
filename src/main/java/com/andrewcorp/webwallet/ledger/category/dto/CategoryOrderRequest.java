package com.andrewcorp.webwallet.ledger.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * New place of a category after drag and drop.
 *
 * @param parentId top-level category to nest it under, null to make it a top-level category
 */
public record CategoryOrderRequest(
        @NotNull Long id,
        @Schema(nullable = true) Long parentId,
        @NotNull Integer sortOrder) {}
