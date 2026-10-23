package com.andrewcorp.webwallet.ledger.category;

import com.andrewcorp.webwallet.ledger.PaletteColor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Two-level category: a top-level category or a subcategory of one.
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    private Long parentId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CategoryKind kind;

    @Column(nullable = false)
    private boolean archived;

    @Column(nullable = false)
    private int sortOrder;

    /** Name of a Lucide icon in kebab case, e.g. {@code shopping-cart}. */
    private String icon;

    @Enumerated(EnumType.STRING)
    private PaletteColor color;

    /** Of an expense category; null on a subcategory means the same as its parent. */
    @Enumerated(EnumType.STRING)
    private Necessity necessity;

    /** Whether the budget rest of the category moves to the next month (an envelope) instead of expiring with it. */
    @Column(nullable = false)
    private boolean carryOver;

    private String externalSource;

    private String externalId;

    protected Category() {}

    public Category(Long userId, Long parentId, String name, CategoryKind kind) {
        this.userId = userId;
        this.parentId = parentId;
        this.name = name;
        this.kind = kind;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CategoryKind getKind() {
        return kind;
    }

    public void setKind(CategoryKind kind) {
        this.kind = kind;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public boolean isCarryOver() {
        return carryOver;
    }

    public void setCarryOver(boolean carryOver) {
        this.carryOver = carryOver;
    }

    public String getExternalSource() {
        return externalSource;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternal(String source, String id) {
        this.externalSource = source;
        this.externalId = id;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public PaletteColor getColor() {
        return color;
    }

    public void setColor(PaletteColor color) {
        this.color = color;
    }

    public Necessity getNecessity() {
        return necessity;
    }

    public void setNecessity(Necessity necessity) {
        this.necessity = necessity;
    }
}
