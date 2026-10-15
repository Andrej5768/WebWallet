package com.andrewcorp.webwallet.ledger.category;

import com.andrewcorp.webwallet.budget.BudgetRepository;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryOrderRequest;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryRequest;
import com.andrewcorp.webwallet.ledger.category.dto.CategoryResponse;
import com.andrewcorp.webwallet.ledger.transaction.TransactionSplitRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
        Category category = new Category(userId, null, request.getName().trim(), request.getKind());
        apply(userId, category, request);
        return CategoryResponse.from(categories.save(category), List.of());
    }

    @Transactional
    public CategoryResponse update(Long userId, Long id, CategoryRequest request) {
        Category category = find(userId, id);
        List<Category> children = categories.findByParentId(category.getId());
        if (category.getKind() != request.getKind()) {
            // Spending would turn into income and envelopes would hold money of the wrong kind
            List<Long> ids =
                    new ArrayList<>(children.stream().map(Category::getId).toList());
            ids.add(category.getId());
            if (splits.existsByCategoryIdIn(ids) || budget.existsByCategoryIdIn(ids)) {
                throw ApiException.invalid("kind", "error.category.kindInUse");
            }
        }
        category.setName(request.getName().trim());
        category.setKind(request.getKind());
        apply(userId, category, request);
        // Subcategories always have the kind of their parent; income is neither a need nor a want
        children.forEach(child -> {
            child.setKind(category.getKind());
            if (category.getKind() == CategoryKind.INCOME) {
                child.setNecessity(null);
            }
        });
        return CategoryResponse.from(
                category,
                children.stream()
                        .map(child -> CategoryResponse.from(child, List.of()))
                        .toList());
    }

    /**
     * Moves and sorts categories at once, e.g. after drag and drop; categories not listed stay where they are. The
     * result must keep the rules of a single update: two levels, and subcategories of the kind of their parent.
     */
    @Transactional
    public List<CategoryResponse> reorder(Long userId, List<CategoryOrderRequest> items) {
        Map<Long, Category> byId = categories.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            CategoryOrderRequest item = items.get(i);
            Category category = byId.get(item.id());
            if (category == null) {
                throw ApiException.invalid("[" + i + "].id", "error.notFound");
            }
            if (!seen.add(item.id())) {
                throw ApiException.invalid("[" + i + "].id", "error.category.duplicate");
            }
            category.setParentId(item.parentId());
            category.setSortOrder(item.sortOrder());
        }
        // Only the moved categories can break the rules: the others keep their parents, kinds and children
        Set<Long> parentIds = byId.values().stream()
                .map(Category::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (int i = 0; i < items.size(); i++) {
            Category category = byId.get(items.get(i).id());
            if (category.getParentId() == null) {
                continue;
            }
            String field = "[" + i + "].parentId";
            Category parent = byId.get(category.getParentId());
            if (parent == null || parent == category || parent.getParentId() != null) {
                throw ApiException.invalid(field, "error.category.parent");
            }
            if (parentIds.contains(category.getId())) {
                throw ApiException.invalid(field, "error.category.hasChildren");
            }
            if (parent.getKind() != category.getKind()) {
                throw ApiException.invalid(field, "error.category.kind");
            }
        }
        return tree(userId);
    }

    /** Only unused categories without subcategories can be deleted; others should be archived. */
    @Transactional
    public void delete(Long userId, Long id) {
        Category category = find(userId, id);
        // Deleting the assignments would silently change the money ready to assign of past months
        if (categories.existsByParentId(category.getId())
                || splits.existsByCategoryIdIn(List.of(category.getId()))
                || budget.existsByCategoryIdIn(List.of(category.getId()))) {
            throw new ApiException(ErrorCode.RESOURCE_IN_USE);
        }
        categories.delete(category);
    }

    @Transactional(readOnly = true)
    public Category find(Long userId, Long id) {
        return categories.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    private void apply(Long userId, Category category, CategoryRequest request) {
        if (request.getParentId() != null) {
            if (Objects.equals(request.getParentId(), category.getId())) {
                throw ApiException.invalid("parentId", "error.category.parent");
            }
            Category parent = categories
                    .findByIdAndUserId(request.getParentId(), userId)
                    .orElseThrow(() -> ApiException.invalid("parentId", "error.category.parent"));
            if (parent.getParentId() != null) {
                throw ApiException.invalid("parentId", "error.category.parent");
            }
            if (category.getId() != null && categories.existsByParentId(category.getId())) {
                throw ApiException.invalid("parentId", "error.category.hasChildren");
            }
            if (parent.getKind() != request.getKind()) {
                throw ApiException.invalid("kind", "error.category.kind");
            }
        }
        category.setParentId(request.getParentId());
        // The parent is always given (null for a top-level category); other omitted fields keep their values
        if (request.getArchived() != null) {
            category.setArchived(request.getArchived());
        }
        if (request.getSortOrder() != null) {
            category.setSortOrder(request.getSortOrder());
        }
        // Sent as null, the icon, color or necessity is removed
        if (request.iconSent()) {
            category.setIcon(request.getIcon());
        }
        if (request.colorSent()) {
            category.setColor(request.getColor());
        }
        if (request.necessitySent()) {
            if (request.getNecessity() != null && category.getKind() == CategoryKind.INCOME) {
                throw ApiException.invalid("necessity", "error.category.necessity");
            }
            category.setNecessity(request.getNecessity());
        } else if (category.getKind() == CategoryKind.INCOME) {
            // An expense category turned into income
            category.setNecessity(null);
        }
    }
}
