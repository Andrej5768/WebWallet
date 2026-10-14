package com.andrewcorp.webwallet.ledger.category;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryRequest;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Categories")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "List categories as a tree of top-level categories and their subcategories")
    @GetMapping
    public List<CategoryResponse> tree(@CurrentUserId Long userId) {
        return categoryService.tree(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@CurrentUserId Long userId, @Valid @RequestBody CategoryRequest request) {
        return categoryService.create(userId, request);
    }

    @PutMapping("/{id}")
    public CategoryResponse update(@CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(userId, id, request);
    }

    @Operation(summary = "Delete an unused category without subcategories; others should be archived")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        categoryService.delete(userId, id);
    }
}
