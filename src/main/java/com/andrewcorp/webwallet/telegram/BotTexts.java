package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.config.WebConfig;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/** Texts of the bot in the language of the user, and the formats of amounts and dates in them. */
@Component
public class BotTexts {

    private static final Map<String, String> SYMBOLS =
            Map.of("UAH", "₴", "USD", "$", "EUR", "€", "GBP", "£", "PLN", "zł");
    private static final char MINUS = '−';

    private final MessageSource messages;

    public BotTexts(MessageSource messages) {
        this.messages = messages;
    }

    public String get(Locale locale, String key, Object... args) {
        return messages.getMessage(key, args.length == 0 ? null : args, locale);
    }

    /** The language of the bot: Ukrainian for {@code uk}, English otherwise. */
    public static Locale locale(String languageTag) {
        return languageTag != null && languageTag.toLowerCase(Locale.ROOT).startsWith("uk")
                ? WebConfig.UKRAINIAN
                : Locale.ENGLISH;
    }

    /** User input inside the HTML of a message. */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** E.g. {@code 1 500,00 ₴}; negative amounts with a minus sign. */
    public static String money(BigDecimal amount, String currency, Locale locale) {
        return (amount.signum() < 0 ? String.valueOf(MINUS) : "") + number(amount.abs(), locale, 2) + " "
                + symbol(currency);
    }

    /** With a plus sign for positive amounts, e.g. {@code +5 000,00 ₴}. */
    public static String signedMoney(BigDecimal amount, String currency, Locale locale) {
        return (amount.signum() > 0 ? "+" : "") + money(amount, currency, locale);
    }

    /** Whole units, for totals of reports, e.g. {@code 28 160 ₴}. */
    public static String wholeMoney(BigDecimal amount, String currency, Locale locale) {
        return (amount.signum() < 0 ? String.valueOf(MINUS) : "")
                + number(amount.abs().setScale(0, RoundingMode.HALF_UP), locale, 0)
                + " "
                + symbol(currency);
    }

    public static String symbol(String currency) {
        return SYMBOLS.getOrDefault(currency, currency);
    }

    /** An expense with a minus, an income with a plus, a transfer without a sign. */
    public static String amount(TransactionType type, BigDecimal amount, String currency, Locale locale) {
        return switch (type) {
            case EXPENSE -> money(amount.negate(), currency, locale);
            case INCOME -> signedMoney(amount, currency, locale);
            case TRANSFER -> money(amount, currency, locale);
        };
    }

    /** A link to a page of the web client for a button; Telegram accepts only public links there. */
    public static Optional<String> buttonUrl(AppProperties properties, String path) {
        String url = properties.frontendUrl(path);
        return url.startsWith("https://") ? Optional.of(url) : Optional.empty();
    }

    /** E.g. {@code 21 жовтня}, {@code 21 October}. */
    public static String date(LocalDate date, Locale locale) {
        return DateTimeFormatter.ofPattern("d MMMM", locale).format(date);
    }

    /** E.g. {@code 21 жовт}, for lists. */
    public static String shortDate(LocalDate date, Locale locale) {
        String month = DateTimeFormatter.ofPattern("MMM", locale).format(date).replace(".", "");
        return date.getDayOfMonth() + " " + month;
    }

    /** E.g. {@code 1–19 жовтня}; one day by its date. */
    public static String period(LocalDate from, LocalDate to, Locale locale) {
        String end = date(to, locale);
        return from.equals(to) ? end : from.getDayOfMonth() + "–" + end;
    }

    /** The month as after «підсумок»: {@code вересня}; in English the name itself. */
    public static String monthOf(YearMonth month, Locale locale) {
        if (!"uk".equals(locale.getLanguage())) {
            return DateTimeFormatter.ofPattern("LLLL", locale).format(month.atDay(1));
        }
        // The month of a date is in the genitive: «1 вересня»
        return DateTimeFormatter.ofPattern("d MMMM", locale)
                .format(month.atDay(1))
                .substring(2);
    }

    /** E.g. {@code Жовтень}. */
    public static String month(YearMonth month, Locale locale) {
        String name = DateTimeFormatter.ofPattern("LLLL", locale).format(month.atDay(1));
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    private static String number(BigDecimal amount, Locale locale, int fraction) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(fraction);
        format.setMaximumFractionDigits(fraction);
        return format.format(amount);
    }
}
