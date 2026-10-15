package com.andrewcorp.webwallet.ledger.tag;

import com.andrewcorp.webwallet.ledger.PaletteColor;

public record TagResponse(Long id, String name, PaletteColor color) {

    static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName(), tag.getColor());
    }
}
