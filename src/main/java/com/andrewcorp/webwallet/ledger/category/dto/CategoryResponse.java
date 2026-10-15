package com.andrewcorp.webwallet.ledger.category.dto;

import com.andrewcorp.webwallet.ledger.PaletteColor;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.Necessity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * @param icon      name of a Lucide icon in kebab case
 * @param necessity of an expense category; null on a subcategory means the same as its parent
 */
public record CategoryResponse(
        Long id,
        String name,
        CategoryKind kind,
        @Schema(nullable = true) Long parentId,
        boolean archived,
        int sortOrder,
        @Schema(nullable = true) String icon,
        @Schema(nullable = true) PaletteColor color,
        @Schema(nullable = true) Necessity necessity,
        List<CategoryResponse> children) {

    public static CategoryResponse from(Category category, List<CategoryResponse> children) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getKind(),
                category.getParentId(),
                category.isArchived(),
                category.getSortOrder(),
                category.getIcon(),
                category.getColor(),
                category.getNecessity(),
                children);
    }
}
