package com.andrewcorp.webwallet.ledger.category.dto;

import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;

import java.util.List;

public record CategoryResponse(
        Long id,
        String name,
        CategoryKind kind,
        Long parentId,
        boolean archived,
        int sortOrder,
        List<CategoryResponse> children) {

    public static CategoryResponse from(Category category, List<CategoryResponse> children) {
        return new CategoryResponse(category.getId(), category.getName(), category.getKind(), category.getParentId(),
                category.isArchived(), category.getSortOrder(), children);
    }
}
