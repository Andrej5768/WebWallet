package com.andrewcorp.webwallet.telegram;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an operation from a message to the bot: «150 кава», «+5000 зарплата», «2000 моно готівка», «89,90 таксі
 * вчора». The first number is the amount, a plus makes it an income; words naming accounts pick the account, two of
 * them make a transfer from the first to the second; a date word or {@code dd.mm} sets the date; the rest is the
 * description.
 */
final class QuickEntryParser {

    private static final Pattern AMOUNT =
            Pattern.compile("([+-]?)(\\d{1,12}(?:[.,]\\d{1,2})?)(?:₴|грн\\.?|uah)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2}|\\d{4}))?");
    private static final Pattern ARROW = Pattern.compile("(?:—>|->|=>|→|>)");
    private static final Map<String, Integer> DAYS_AGO = Map.of(
            "сьогодні",
            0,
            "сегодня",
            0,
            "today",
            0,
            "вчора",
            1,
            "вчера",
            1,
            "yesterday",
            1,
            "позавчора",
            2,
            "позавчера",
            2);
    /** Words between two accounts of a transfer, e.g. «з моно на готівку». */
    private static final Set<String> LINKS = Set.of("на", "в", "у", "to", "з", "із", "зі", "с", "from");

    private static final int MAX_MENTIONS = 2;

    private QuickEntryParser() {}

    record AccountName(Long id, String name) {}

    /** A word naming accounts; more than one account means the user has to choose. */
    record Mention(String word, List<Long> accountIds) {}

    /**
     * @param income      the amount had a plus
     * @param mentions    up to two words naming accounts, in the order of the message
     * @param description the other words, empty when there are none
     */
    record Parsed(BigDecimal amount, boolean income, LocalDate date, List<Mention> mentions, String description) {}

    /** Empty when the message has no amount. */
    static Optional<Parsed> parse(String text, List<AccountName> accounts, LocalDate today) {
        List<String> words = new ArrayList<>(
                Arrays.asList(ARROW.matcher(text).replaceAll(" ").trim().split("\\s+")));
        BigDecimal amount = null;
        boolean income = false;
        LocalDate date = today;
        boolean dateSet = false;
        List<String> rest = new ArrayList<>();
        for (String word : words) {
            Matcher amountMatch = AMOUNT.matcher(word);
            if (amount == null && amountMatch.matches()) {
                amount = new BigDecimal(amountMatch.group(2).replace(',', '.'));
                income = "+".equals(amountMatch.group(1));
                continue;
            }
            if (!dateSet) {
                Optional<LocalDate> parsedDate = date(word, today);
                if (parsedDate.isPresent()) {
                    date = parsedDate.get();
                    dateSet = true;
                    continue;
                }
            }
            rest.add(word);
        }
        if (amount == null || amount.signum() == 0) {
            return Optional.empty();
        }

        List<Mention> mentions = new ArrayList<>();
        List<String> description = new ArrayList<>();
        for (int i = 0; i < rest.size(); i++) {
            String word = rest.get(i);
            if (mentions.size() < MAX_MENTIONS) {
                List<Long> named = named(word, accounts);
                if (!named.isEmpty()) {
                    mentions.add(new Mention(word, named));
                    continue;
                }
                boolean linksAccount = LINKS.contains(word.toLowerCase(Locale.ROOT))
                        && i + 1 < rest.size()
                        && !named(rest.get(i + 1), accounts).isEmpty();
                if (linksAccount) {
                    continue;
                }
            }
            description.add(word);
        }
        return Optional.of(new Parsed(amount, income, date, mentions, String.join(" ", description)));
    }

    private static List<Long> named(String word, List<AccountName> accounts) {
        return accounts.stream()
                .filter(account -> AccountNames.matches(word, account.name()))
                .map(AccountName::id)
                .toList();
    }

    /** A date word, or {@code dd.mm} of this year (of the last one when it is still ahead), or {@code dd.mm.yy}. */
    private static Optional<LocalDate> date(String word, LocalDate today) {
        Integer daysAgo = DAYS_AGO.get(word.toLowerCase(Locale.ROOT));
        if (daysAgo != null) {
            return Optional.of(today.minusDays(daysAgo));
        }
        Matcher match = DATE.matcher(word);
        if (!match.matches()) {
            return Optional.empty();
        }
        try {
            int day = Integer.parseInt(match.group(1));
            int month = Integer.parseInt(match.group(2));
            if (match.group(3) != null) {
                int year = Integer.parseInt(match.group(3));
                return Optional.of(LocalDate.of(year < 100 ? 2000 + year : year, month, day));
            }
            LocalDate date = LocalDate.of(today.getYear(), month, day);
            return Optional.of(date.isAfter(today) ? date.minusYears(1) : date);
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
