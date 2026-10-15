package com.andrewcorp.webwallet.common.util;

public final class Texts {

    private Texts() {}

    /** The trimmed text, or null for a null or blank text. */
    public static String trimToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** The trimmed text cut to the maximum length, or null for a null or blank text. */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
