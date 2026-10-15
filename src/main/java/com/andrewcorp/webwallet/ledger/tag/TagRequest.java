package com.andrewcorp.webwallet.ledger.tag;

import com.andrewcorp.webwallet.ledger.PaletteColor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for creating or updating a tag. An omitted color keeps its value, a color sent as null is removed, so this is
 * a class that remembers whether the color was sent rather than a record.
 */
public class TagRequest {

    @NotBlank @Size(max = 100) private String name;

    private PaletteColor color;

    private boolean colorSent;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PaletteColor getColor() {
        return color;
    }

    public void setColor(PaletteColor color) {
        this.color = color;
        this.colorSent = true;
    }

    /** Whether the color was sent, possibly as null. */
    public boolean colorSent() {
        return colorSent;
    }
}
