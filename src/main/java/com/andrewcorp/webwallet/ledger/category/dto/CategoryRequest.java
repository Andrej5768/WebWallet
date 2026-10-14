package com.andrewcorp.webwallet.ledger.category.dto;

import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param parentId top-level category to nest this one under; subcategories cannot have children
 */
public record CategoryRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull CategoryKind kind,
        Long parentId,
        Boolean archived,
        Integer sortOrder) {}
