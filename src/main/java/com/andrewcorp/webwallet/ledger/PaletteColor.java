package com.andrewcorp.webwallet.ledger;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Color of a category or a tag: a key of the palette of the clients, which pick the shade for the theme. */
public enum PaletteColor {
    BLUE,
    GREEN,
    ORANGE,
    VIOLET,
    TEAL,
    PINK,
    AMBER,
    SLATE,
    RED,
    INDIGO,
    CYAN,
    LIME;

    /** Written and read in lower case, e.g. {@code "blue"}. */
    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
