package com.andrewcorp.webwallet.common.util;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Amounts written by people and banks: "1 234,56", "1234.56", with any kind of space between the thousands. */
public final class Amounts {

    /** Spaces, line breaks and the no-break and thin spaces that banks and spreadsheets put between thousands. */
    private static final Pattern GROUPING = Pattern.compile("[\\s\\u00A0\\u2007\\u2009\\u202F]");

    private Amounts() {}

    /** The text without the spaces between the thousands. */
    public static String compact(String text) {
        return GROUPING.matcher(text).replaceAll("");
    }

    /**
     * The amount of the text, with a comma or a dot before the decimals.
     *
     * @throws NumberFormatException when it is not a number
     */
    public static BigDecimal parse(String text) {
        return new BigDecimal(compact(text).replace(',', '.'));
    }
}
