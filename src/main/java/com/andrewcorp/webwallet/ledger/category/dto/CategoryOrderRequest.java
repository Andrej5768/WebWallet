package com.andrewcorp.webwallet.ledger.category.dto;

import jakarta.validation.constraints.NotNull;

/**
 * New place of a category after drag and drop.
 *
 * @param parentId top-level category to nest it under, null to make it a top-level category
 */
public record CategoryOrderRequest(
        @NotNull Long id, Long parentId, @NotNull Integer sortOrder) {}
