package com.andrewcorp.webwallet.ledger.tag;

import com.andrewcorp.webwallet.ledger.PaletteColor;
import io.swagger.v3.oas.annotations.media.Schema;

public record TagResponse(
        Long id, String name, @Schema(nullable = true) PaletteColor color) {

    static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName(), tag.getColor());
    }
}
