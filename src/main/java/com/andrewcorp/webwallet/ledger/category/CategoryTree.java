package com.andrewcorp.webwallet.ledger.category;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The categories of a user, loaded once, with their two levels: top-level categories and their subcategories, in
 * the order of the user.
 */
public final class CategoryTree {

    private final List<Category> all;
    private final Map<Long, Category> byId = new HashMap<>();

    private CategoryTree(List<Category> all) {
        this.all = all;
        all.forEach(category -> byId.put(category.getId(), category));
    }

    /** @param ordered the categories in the order they are shown */
    public static CategoryTree of(List<Category> ordered) {
        return new CategoryTree(List.copyOf(ordered));
    }

    public Category get(Long id) {
        return id != null ? byId.get(id) : null;
    }

    public String name(Long id) {
        Category category = get(id);
        return category != null ? category.getName() : null;
    }

    /** The top-level category of the category; null for no category. */
    public Long topLevel(Long id) {
        Category category = get(id);
        if (category == null) {
            return id;
        }
        return category.getParentId() != null ? category.getParentId() : id;
    }

    /** The top-level categories of both kinds. */
    public List<Category> roots() {
        return all.stream().filter(category -> category.getParentId() == null).toList();
    }

    /** The top-level categories of the kind. */
    public List<Category> topLevel(CategoryKind kind) {
        return all.stream()
                .filter(category -> category.getParentId() == null && category.getKind() == kind)
                .toList();
    }

    public List<Category> children(Long parentId) {
        return all.stream()
                .filter(category -> parentId.equals(category.getParentId()))
                .toList();
    }

    /** The categories and their subcategories. */
    public Set<Long> withSubcategories(Collection<Long> ids) {
        Set<Long> result = new HashSet<>(ids);
        for (Category category : all) {
            if (category.getParentId() != null && ids.contains(category.getParentId())) {
                result.add(category.getId());
            }
        }
        return result;
    }

    /** The necessity of the category, or of its parent when it has none. */
    public Necessity necessity(Long id) {
        Category category = get(id);
        if (category == null) {
            return null;
        }
        if (category.getNecessity() != null || category.getParentId() == null) {
            return category.getNecessity();
        }
        Category parent = byId.get(category.getParentId());
        return parent != null ? parent.getNecessity() : null;
    }

    /** E.g. «Їжа › Кава»; a top-level category by its name alone. */
    public String path(Category category) {
        Category parent = category.getParentId() != null ? byId.get(category.getParentId()) : null;
        return parent != null ? parent.getName() + " › " + category.getName() : category.getName();
    }
}
