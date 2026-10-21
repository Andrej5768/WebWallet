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
 * вчора», «1 500 продукти», «12.10 150 кава». The first number is the amount (a plus makes it an income, digit
 * groups separated by spaces belong to it), unless it is a {@code dd.mm} date followed by another number; a currency
 * after it («100 грн») must be that of the account. Words naming accounts pick the account, two of them make a
 * transfer: from the first to the second, or as «з» / «на» say. A date word or {@code dd.mm} sets the date; the rest
 * is the description.
 */
final class QuickEntryParser {

    /** A number with an optional sign, currency symbol before it and currency word or symbol glued after it. */
    private static final Pattern NUMBER =
            Pattern.compile("([+-]?)([₴$€]?)(\\d{1,12}(?:[.,]\\d{1,2})?)([\\p{L}.₴$€]*)", Pattern.CASE_INSENSITIVE);
    /** A group of three digits continuing an amount written with spaces, e.g. the «500» of «1 500». */
    private static final Pattern THOUSANDS = Pattern.compile("(\\d{3}(?:[.,]\\d{1,2})?)([\\p{L}.₴$€]*)");

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
    private static final Map<String, String> CURRENCIES = Map.ofEntries(
            Map.entry("₴", "UAH"),
            Map.entry("грн", "UAH"),
            Map.entry("грн.", "UAH"),
            Map.entry("гривень", "UAH"),
            Map.entry("гривні", "UAH"),
            Map.entry("uah", "UAH"),
            Map.entry("$", "USD"),
            Map.entry("usd", "USD"),
            Map.entry("дол", "USD"),
            Map.entry("дол.", "USD"),
            Map.entry("доларів", "USD"),
            Map.entry("€", "EUR"),
            Map.entry("eur", "EUR"),
            Map.entry("євро", "EUR"),
            Map.entry("евро", "EUR"));
    /** Words before the account the money goes to, e.g. «на готівку». */
    private static final Set<String> TO = Set.of("на", "в", "у", "to");
    /** Words before the account the money comes from, e.g. «з моно». */
    private static final Set<String> FROM = Set.of("з", "із", "зі", "с", "from");

    private static final int MAX_MENTIONS = 2;

    private QuickEntryParser() {}

    record AccountName(Long id, String name) {}

    /** Where the money of a transfer goes for an account word: said by a word before it, or not. */
    enum Role {
        FROM,
        TO,
        UNSAID
    }

    /** A word naming accounts; more than one account means the user has to choose. */
    record Mention(String word, List<Long> accountIds, Role role) {}

    /**
     * @param income      the amount had a plus
     * @param currency    the currency written with the amount, or null
     * @param mentions    up to two words naming accounts, in the order of the message
     * @param description the other words, empty when there are none
     */
    record Parsed(
            BigDecimal amount,
            boolean income,
            String currency,
            LocalDate date,
            List<Mention> mentions,
            String description) {}

    /** Empty when the message has no amount. */
    static Optional<Parsed> parse(String text, List<AccountName> accounts, LocalDate today) {
        List<String> words = new ArrayList<>(
                Arrays.asList(ARROW.matcher(text).replaceAll(" ").trim().split("\\s+")));
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            Matcher number = NUMBER.matcher(words.get(i));
            if (number.matches() && (number.group(4).isEmpty() || currency(number.group(4)) != null)) {
                numbers.add(i);
            }
        }
        if (numbers.isEmpty()) {
            return Optional.empty();
        }
        // «12.10 150 кава»: a date first, then the amount
        int amountAt = numbers.get(0);
        if (numbers.size() > 1
                && date(words.get(amountAt), today).isPresent()
                && DATE.matcher(words.get(amountAt)).matches()) {
            amountAt = numbers.get(1);
        }

        Matcher number = NUMBER.matcher(words.get(amountAt));
        number.matches();
        boolean income = "+".equals(number.group(1));
        String digits = number.group(3);
        String currency = currency(number.group(2).isEmpty() ? number.group(4) : number.group(2));
        int last = amountAt;
        // «1 500»: groups of three digits after an amount of one to three digits without a decimal part
        if (digits.length() <= 3 && number.group(4).isEmpty() && !digits.contains(".") && !digits.contains(",")) {
            while (last + 1 < words.size()) {
                Matcher group = THOUSANDS.matcher(words.get(last + 1));
                if (!group.matches() || (!group.group(2).isEmpty() && currency(group.group(2)) == null)) {
                    break;
                }
                digits += group.group(1);
                last++;
                if (!group.group(2).isEmpty()) {
                    currency = currency(group.group(2));
                    break;
                }
            }
        }
        if (currency == null && last + 1 < words.size() && currency(words.get(last + 1)) != null) {
            currency = currency(words.get(last + 1));
            last++;
        }
        BigDecimal amount = new BigDecimal(digits.replace(',', '.'));
        if (amount.signum() == 0) {
            return Optional.empty();
        }

        LocalDate date = today;
        boolean dateSet = false;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            if (i >= amountAt && i <= last) {
                continue;
            }
            String word = words.get(i);
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

        List<Mention> mentions = new ArrayList<>();
        List<String> description = new ArrayList<>();
        Role role = Role.UNSAID;
        for (int i = 0; i < rest.size(); i++) {
            String word = rest.get(i);
            if (mentions.size() < MAX_MENTIONS) {
                List<Long> named = named(word, accounts);
                if (!named.isEmpty()) {
                    mentions.add(new Mention(word, named, role));
                    role = Role.UNSAID;
                    continue;
                }
                String lower = word.toLowerCase(Locale.ROOT);
                boolean linksAccount = (TO.contains(lower) || FROM.contains(lower))
                        && i + 1 < rest.size()
                        && !named(rest.get(i + 1), accounts).isEmpty();
                if (linksAccount) {
                    role = TO.contains(lower) ? Role.TO : Role.FROM;
                    continue;
                }
            }
            description.add(word);
        }
        return Optional.of(new Parsed(amount, income, currency, date, mentions, String.join(" ", description)));
    }

    /** The currency code of a symbol or word, or null. */
    private static String currency(String word) {
        return word == null || word.isEmpty() ? null : CURRENCIES.get(word.toLowerCase(Locale.ROOT));
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
