package com.andrewcorp.webwallet.user.dto;

/**
 * @param keepCategories keep the categories and the categorization rules (and the payees the rules need); false by
 *                       default
 * @param confirm        must be exactly {@code DELETE}
 */
public record ResetRequest(Boolean keepCategories, String confirm) {

    public static final String CONFIRMATION = "DELETE";
}
