package com.andrewcorp.webwallet.ledger.category.dto;

import com.andrewcorp.webwallet.ledger.PaletteColor;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.Necessity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for creating or updating a category. The parent is always given (null for a top-level category); other
 * omitted fields keep their values. Icon, color and necessity sent as null are removed, so this is a class that
 * remembers which of them were sent rather than a record.
 */
public class CategoryRequest {

    @NotBlank @Size(max = 100) private String name;

    @NotNull private CategoryKind kind;

    /** Top-level category to nest this one under; subcategories cannot have children. */
    @Schema(nullable = true)
    private Long parentId;

    private Boolean archived;

    private Integer sortOrder;

    /** Name of a Lucide icon in kebab case, e.g. {@code shopping-cart}. */
    @Schema(nullable = true)
    @Pattern(regexp = "[a-z0-9-]{1,50}") private String icon;

    @Schema(nullable = true)
    private PaletteColor color;

    /** Only for expense categories; null on a subcategory means the same as its parent. */
    @Schema(nullable = true)
    private Necessity necessity;

    private boolean iconSent;
    private boolean colorSent;
    private boolean necessitySent;

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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Boolean getArchived() {
        return archived;
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
        this.iconSent = true;
    }

    public PaletteColor getColor() {
        return color;
    }

    public void setColor(PaletteColor color) {
        this.color = color;
        this.colorSent = true;
    }

    public Necessity getNecessity() {
        return necessity;
    }

    public void setNecessity(Necessity necessity) {
        this.necessity = necessity;
        this.necessitySent = true;
    }

    /** Whether the icon was sent, possibly as null. */
    public boolean iconSent() {
        return iconSent;
    }

    public boolean colorSent() {
        return colorSent;
    }

    public boolean necessitySent() {
        return necessitySent;
    }
}
