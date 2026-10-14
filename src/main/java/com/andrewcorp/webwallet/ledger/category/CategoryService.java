package com.andrewcorp.webwallet.ledger.category;

import com.andrewcorp.webwallet.budget.BudgetRepository;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryRequest;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryResponse;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSplitRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final TransactionSplitRepository splits;
    private final BudgetRepository budget;

    public CategoryService(CategoryRepository categories, TransactionSplitRepository splits, BudgetRepository budget) {
        this.categories = categories;
        this.splits = splits;
        this.budget = budget;
    }

    /** All categories of the user as a tree: top-level categories with their subcategories. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> tree(Long userId) {
        List<Category> all = categories.findByUserIdOrderBySortOrderAscNameAsc(userId);
        Map<Long, List<Category>> childrenByParent = all.stream()
                .filter(category -> category.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId));
        return all.stream()
                .filter(category -> category.getParentId() == null)
                .map(parent -> CategoryResponse.from(
                        parent,
                        childrenByParent.getOrDefault(parent.getId(), List.of()).stream()
                                .map(child -> CategoryResponse.from(child, List.of()))
                                .toList()))
                .toList();
    }

    @Transactional
    public CategoryResponse create(Long userId, CategoryRequest request) {
        Category category = new Category(userId, null, request.name().trim(), request.kind());
        apply(userId, category, request);
        return CategoryResponse.from(categories.save(category), List.of());
    }

    @Transactional
    public CategoryResponse update(Long userId, Long id, CategoryRequest request) {
        Category category = find(userId, id);
        List<Category> children = categories.findByParentId(category.getId());
        if (category.getKind() != request.kind()) {
            // Spending would turn into income and envelopes would hold money of the wrong kind
            List<Long> ids =
                    new ArrayList<>(children.stream().map(Category::getId).toList());
            ids.add(category.getId());
            if (splits.existsByCategoryIdIn(ids) || budget.existsByCategoryIdIn(ids)) {
                throw ApiException.invalid("kind", "error.category.kindInUse");
            }
        }
        category.setName(request.name().trim());
        category.setKind(request.kind());
        apply(userId, category, request);
        // Subcategories always have the kind of their parent
        children.forEach(child -> child.setKind(category.getKind()));
        return CategoryResponse.from(
                category,
                children.stream()
                        .map(child -> CategoryResponse.from(child, List.of()))
                        .toList());
    }

    /** Only unused categories without subcategories can be deleted; others should be archived. */
    @Transactional
    public void delete(Long userId, Long id) {
        Category category = find(userId, id);
        if (categories.existsByParentId(category.getId()) || splits.existsByCategoryIdIn(List.of(category.getId()))) {
            throw new ApiException(ErrorCode.RESOURCE_IN_USE);
        }
        categories.delete(category);
    }

    @Transactional(readOnly = true)
    public Category find(Long userId, Long id) {
        return categories.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    private void apply(Long userId, Category category, CategoryRequest request) {
        if (request.parentId() != null) {
            if (Objects.equals(request.parentId(), category.getId())) {
                throw ApiException.invalid("parentId", "error.category.parent");
            }
            Category parent = categories
                    .findByIdAndUserId(request.parentId(), userId)
                    .orElseThrow(() -> ApiException.invalid("parentId", "error.category.parent"));
            if (parent.getParentId() != null) {
                throw ApiException.invalid("parentId", "error.category.parent");
            }
            if (category.getId() != null && categories.existsByParentId(category.getId())) {
                throw ApiException.invalid("parentId", "error.category.hasChildren");
            }
            if (parent.getKind() != request.kind()) {
                throw ApiException.invalid("kind", "error.category.kind");
            }
        }
        category.setParentId(request.parentId());
        // The parent is always given (null for a top-level category); other omitted fields keep their values
        if (request.archived() != null) {
            category.setArchived(request.archived());
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
    }
}
