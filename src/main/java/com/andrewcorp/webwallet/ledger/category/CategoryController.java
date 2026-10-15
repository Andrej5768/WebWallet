package com.andrewcorp.webwallet.ledger.category;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryOrderRequest;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryRequest;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    @Operation(
            summary = "Update a category",
            description = "The parent is always applied (null makes a top-level category); other omitted optional"
                    + " fields keep their values. icon, color and necessity sent as null are removed. A necessity"
                    + " is allowed only on expense categories; it is removed when the category becomes income.")
    @PutMapping("/{id}")
    public CategoryResponse update(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(userId, id, request);
    }

    @Operation(
            summary = "Move and sort categories at once, e.g. after drag and drop",
            description = "Applies all items or none. Categories not listed stay where they are. Only two levels are"
                    + " allowed: a category with subcategories cannot get a parent, and a subcategory must have the"
                    + " kind of its parent. Errors name the item, e.g. the field [2].parentId. Returns the whole"
                    + " tree like the list.")
    @PutMapping("/order")
    public List<CategoryResponse> reorder(
            @CurrentUserId Long userId, @RequestBody @Size(max = 1000) List<@Valid CategoryOrderRequest> items) {
        return categoryService.reorder(userId, items);
    }

    @Operation(summary = "Delete an unused category without subcategories; others should be archived")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        categoryService.delete(userId, id);
    }
}
