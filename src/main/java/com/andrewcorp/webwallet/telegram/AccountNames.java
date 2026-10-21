package com.andrewcorp.webwallet.telegram;

import java.util.Locale;
import java.util.Map;

/**
 * Tells whether a word of a message names an account. Both are compared in Latin letters, so that «моно» names
 * «Monobank» and «приват» «ПриватБанк»; a word of at least five letters may differ in its ending («готівку»).
 */
final class AccountNames {

    private static final int MIN_LENGTH = 3;
    private static final int ENDING_FROM = 5;
    private static final Map<Character, String> LATIN = Map.ofEntries(
            Map.entry('а', "a"),
            Map.entry('б', "b"),
            Map.entry('в', "v"),
            Map.entry('г', "g"),
            Map.entry('ґ', "g"),
            Map.entry('д', "d"),
            Map.entry('е', "e"),
            Map.entry('є', "e"),
            Map.entry('ё', "e"),
            Map.entry('ж', "zh"),
            Map.entry('з', "z"),
            Map.entry('и', "i"),
            Map.entry('і', "i"),
            Map.entry('ї', "i"),
            Map.entry('й', "i"),
            Map.entry('к', "k"),
            Map.entry('л', "l"),
            Map.entry('м', "m"),
            Map.entry('н', "n"),
            Map.entry('о', "o"),
            Map.entry('п', "p"),
            Map.entry('р', "r"),
            Map.entry('с', "s"),
            Map.entry('т', "t"),
            Map.entry('у', "u"),
            Map.entry('ф', "f"),
            Map.entry('х', "h"),
            Map.entry('ц', "c"),
            Map.entry('ч', "ch"),
            Map.entry('ш', "sh"),
            Map.entry('щ', "sch"),
            Map.entry('ы', "i"),
            Map.entry('э', "e"),
            Map.entry('ю', "u"),
            Map.entry('я', "a"),
            Map.entry('y', "i"),
            Map.entry('j', "i"),
            Map.entry('w', "v"),
            Map.entry('q', "k"));

    private AccountNames() {}

    static boolean matches(String word, String accountName) {
        String normalized = normalize(word);
        if (normalized.length() < MIN_LENGTH) {
            return false;
        }
        String stem =
                normalized.length() >= ENDING_FROM ? normalized.substring(0, normalized.length() - 1) : normalized;
        if (normalize(accountName).startsWith(stem)) {
            return true;
        }
        for (String part : accountName.split("[^\\p{L}\\p{N}]+")) {
            if (normalize(part).startsWith(stem)) {
                return true;
            }
        }
        return false;
    }

    /** Lower-case Latin letters and digits only. */
    static String normalize(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            String latin = LATIN.get(c);
            if (latin != null) {
                result.append(latin);
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                result.append(c);
            }
        }
        return result.toString();
    }
}
