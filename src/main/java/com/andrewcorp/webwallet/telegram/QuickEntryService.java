package com.andrewcorp.webwallet.telegram;

import static com.andrewcorp.webwallet.telegram.BotTexts.escape;

import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.tag.Tag;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionService;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionRequest;
import com.andrewcorp.webwallet.telegram.QuickEntryParser.AccountName;
import com.andrewcorp.webwallet.telegram.QuickEntryParser.Mention;
import com.andrewcorp.webwallet.telegram.QuickEntryParser.Parsed;
import com.andrewcorp.webwallet.telegram.QuickEntryParser.Role;
import com.andrewcorp.webwallet.telegram.TelegramApi.Button;
import com.andrewcorp.webwallet.telegram.TelegramApi.Keyboard;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operations written to the bot: records them right away and answers with the operation and the buttons that change
 * its category or account, turn an expense into a transfer, or cancel it.
 */
@Service
class QuickEntryService {

    /** The buttons under an operation change it for this long after it was recorded. */
    static final Duration EDITABLE = Duration.ofHours(48);

    private static final int PICKER_SIZE = 6;
    private static final int FREQUENT_DAYS = 90;

    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final TransactionService transactionService;
    private final CategoryGuesser guesser;
    private final BotTexts texts;
    private final AppProperties properties;
    private final Clock clock;

    QuickEntryService(
            AccountRepository accounts,
            CategoryRepository categories,
            TransactionRepository transactions,
            TransactionService transactionService,
            CategoryGuesser guesser,
            BotTexts texts,
            AppProperties properties,
            Clock clock) {
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.transactionService = transactionService;
        this.guesser = guesser;
        this.texts = texts;
        this.properties = properties;
        this.clock = clock;
    }

    /** What a button did: the new text of its message, and a short notice to show, if any. */
    record Outcome(BotReply reply, String notice) {}

    /**
     * Records the operation of the message.
     *
     * @param chosen accounts the user already picked for the ambiguous account words, in their order
     */
    @Transactional
    BotReply record(TelegramLink link, Locale locale, String text, List<Long> chosen) {
        Long userId = link.getUserId();
        List<Account> open = openAccounts(userId);
        Optional<Parsed> parsed = QuickEntryParser.parse(
                text,
                open.stream()
                        .map(account -> new AccountName(account.getId(), account.getName()))
                        .toList(),
                LocalDate.now(clock));
        if (parsed.isEmpty()) {
            return BotReply.text(texts.get(locale, "bot.entry.noAmount"));
        }
        Parsed entry = parsed.get();

        List<Long> resolved = new ArrayList<>();
        for (int i = 0; i < entry.mentions().size(); i++) {
            Mention mention = entry.mentions().get(i);
            Long pick = i < chosen.size() && mention.accountIds().contains(chosen.get(i)) ? chosen.get(i) : null;
            if (pick == null && mention.accountIds().size() == 1) {
                pick = mention.accountIds().get(0);
            }
            if (pick == null) {
                return ambiguous(mention, resolved, open, locale);
            }
            resolved.add(pick);
        }

        Account account;
        Account to = null;
        TransactionType type;
        if (resolved.size() == 2) {
            // «на готівку з моно»: the words before the accounts say the direction, otherwise their order does
            boolean reversed = entry.mentions().get(0).role() == Role.TO
                    || entry.mentions().get(1).role() == Role.FROM;
            account = find(open, resolved.get(reversed ? 1 : 0));
            to = find(open, resolved.get(reversed ? 0 : 1));
            if (account.getId().equals(to.getId())) {
                return BotReply.text(texts.get(locale, "bot.entry.sameAccount"));
            }
            if (!account.getCurrency().equals(to.getCurrency())) {
                return BotReply.text(texts.get(locale, "bot.entry.currencies"));
            }
            type = TransactionType.TRANSFER;
        } else {
            // «100 грн»: without an account named, the default one in that currency
            List<Account> candidates = entry.currency() == null
                    ? open
                    : open.stream()
                            .filter(candidate -> candidate.getCurrency().equals(entry.currency()))
                            .toList();
            account = resolved.size() == 1 ? find(open, resolved.get(0)) : defaultAccount(link, candidates);
            if (account == null) {
                return BotReply.text(
                        entry.currency() == null
                                ? texts.get(locale, "bot.entry.noAccount")
                                : texts.get(locale, "bot.entry.noAccountInCurrency", entry.currency()));
            }
            type = entry.income() ? TransactionType.INCOME : TransactionType.EXPENSE;
        }
        if (entry.currency() != null && !account.getCurrency().equals(entry.currency())) {
            return BotReply.text(texts.get(
                    locale,
                    "bot.entry.otherCurrency",
                    escape(account.getName()),
                    account.getCurrency(),
                    entry.currency()));
        }

        String description = Texts.truncate(entry.description(), Transaction.MAX_COMMENT_LENGTH);
        CategoryGuesser.Guess guess = type == TransactionType.TRANSFER || description == null
                ? new CategoryGuesser.Guess(null, null)
                : guesser.guess(userId, description, kind(type));
        TransactionRequest request = new TransactionRequest(
                type,
                entry.date(),
                account.getId(),
                entry.amount(),
                to != null ? to.getId() : null,
                null,
                null,
                null,
                guess.category() != null ? guess.category().getId() : null,
                null,
                guess.payee() != null ? guess.payee().getId() : null,
                null,
                description,
                null,
                null);
        Long id = transactionService.create(userId, request).id();
        Transaction transaction = transactions.findById(id).orElseThrow();
        return new BotReply(view(transaction, locale, null), mainKeyboard(transaction, locale));
    }

    /** What a button under a recorded operation does; see {@link #keyboard} for the actions. */
    @Transactional
    Outcome action(Long userId, Locale locale, String action, long transactionId, Long argument) {
        Transaction transaction = transactions
                .findById(transactionId)
                .filter(found -> found.getUserId().equals(userId))
                .orElse(null);
        if (transaction == null) {
            return new Outcome(BotReply.text(texts.get(locale, "bot.entry.deleted")), null);
        }
        if (transaction.getCreatedAt().isBefore(clock.instant().minus(EDITABLE))) {
            return new Outcome(BotReply.text(view(transaction, locale, null)), texts.get(locale, "bot.entry.expired"));
        }
        String learned = null;
        switch (action) {
            case "c" -> {
                return reply(new BotReply(view(transaction, locale, null), categoryPicker(transaction, locale)));
            }
            case "a" -> {
                return reply(new BotReply(view(transaction, locale, null), accountPicker(transaction, "as", locale)));
            }
            case "x" -> {
                String question = "\n\n<b>" + texts.get(locale, "bot.entry.transferTo") + "</b>";
                return reply(new BotReply(
                        view(transaction, locale, null) + question, accountPicker(transaction, "xs", locale)));
            }
            case "cs" -> {
                Category category = categories
                        .findByIdAndUserId(argument, userId)
                        .filter(found -> found.getKind() == kind(transaction.getType()) && !found.isArchived())
                        .orElse(null);
                if (category != null && transaction.getType() != TransactionType.TRANSFER) {
                    update(
                            userId,
                            transaction,
                            transaction.getType(),
                            transaction.getAccount().getId(),
                            null,
                            category.getId());
                    if (transaction.getPayee() == null && transaction.getComment() != null) {
                        learned = texts.get(locale, "bot.entry.learned", escape(transaction.getComment()));
                    }
                }
            }
            case "as" -> {
                Account account = sameCurrency(transaction, argument);
                if (account != null) {
                    update(
                            userId,
                            transaction,
                            transaction.getType(),
                            account.getId(),
                            transaction.getToAccount() != null
                                    ? transaction.getToAccount().getId()
                                    : null,
                            categoryId(transaction));
                }
            }
            case "xs" -> {
                Account to = sameCurrency(transaction, argument);
                if (to != null && transaction.getType() == TransactionType.EXPENSE) {
                    update(
                            userId,
                            transaction,
                            TransactionType.TRANSFER,
                            transaction.getAccount().getId(),
                            to.getId(),
                            null);
                }
            }
            case "d" -> {
                String cancelled = "<b>" + texts.get(locale, "bot.entry.cancelled") + "</b>\n"
                        + escape(Objects.requireNonNullElse(transaction.getComment(), ""))
                        + " " + amount(transaction, locale);
                transactionService.delete(userId, transaction.getId());
                return reply(BotReply.text(cancelled.replace("\n ", "\n")));
            }
            default -> {
                // "b": back to the operation
            }
        }
        return reply(new BotReply(view(transaction, locale, learned), mainKeyboard(transaction, locale)));
    }

    /**
     * The text of a recorded operation: the kind and the amount, the category (or the accounts of a transfer), the
     * account and the date.
     */
    String view(Transaction transaction, Locale locale, String note) {
        StringBuilder text = new StringBuilder("<b>")
                .append(texts.get(
                        locale, "bot.entry." + transaction.getType().name().toLowerCase(Locale.ROOT)))
                .append(' ')
                .append(amount(transaction, locale))
                .append("</b>\n");
        String date = transaction.getDate().equals(LocalDate.now(clock))
                ? texts.get(locale, "bot.entry.today")
                : BotTexts.date(transaction.getDate(), locale);
        if (transaction.getType() == TransactionType.TRANSFER) {
            text.append(escape(transaction.getAccount().getName()))
                    .append(" → ")
                    .append(escape(transaction.getToAccount().getName()))
                    .append('\n')
                    .append(date);
            if (transaction.getComment() != null) {
                text.append(" · ").append(escape(transaction.getComment()));
            }
        } else {
            Category category = transaction.singleCategory();
            String categoryName = category != null ? path(category) : texts.get(locale, "bot.uncategorized");
            text.append(escape(categoryName));
            if (transaction.getComment() != null
                    && (category == null || !category.getName().equalsIgnoreCase(transaction.getComment()))) {
                text.append(" · ").append(escape(transaction.getComment()));
            }
            text.append('\n')
                    .append(escape(transaction.getAccount().getName()))
                    .append(" · ")
                    .append(date);
        }
        if (note != null) {
            text.append("\n<i>").append(note).append("</i>");
        }
        return text.toString();
    }

    /**
     * The buttons of an operation. Their data is {@code <action>:<transaction id>[:<argument>]}: {@code c} and
     * {@code cs} choose and set the category, {@code a} and {@code as} the account, {@code x} and {@code xs} the
     * target account of a transfer, {@code d} cancels, {@code b} goes back.
     */
    private Keyboard mainKeyboard(Transaction transaction, Locale locale) {
        long id = transaction.getId();
        Button cancel = Button.callback(texts.get(locale, "bot.button.cancel"), "d:" + id);
        if (transaction.getType() == TransactionType.TRANSFER) {
            return Keyboard.of(List.of(List.of(cancel)));
        }
        String categoryLabel =
                transaction.singleCategory() != null ? "bot.button.category" : "bot.button.chooseCategory";
        Button category = Button.callback(texts.get(locale, categoryLabel), "c:" + id);
        Button account = Button.callback(texts.get(locale, "bot.button.account"), "a:" + id);
        if (transaction.getType() == TransactionType.INCOME) {
            return Keyboard.of(List.of(List.of(category, account), List.of(cancel)));
        }
        Button transfer = Button.callback(texts.get(locale, "bot.button.transfer"), "x:" + id);
        return Keyboard.of(List.of(List.of(category, account), List.of(transfer, cancel)));
    }

    /** The categories used most often in the last 90 days, then the others in their order. */
    private Keyboard categoryPicker(Transaction transaction, Locale locale) {
        Long userId = transaction.getUserId();
        CategoryKind kind = kind(transaction.getType());
        Long current = categoryId(transaction);
        Set<Long> ids = new LinkedHashSet<>(transactions.findFrequentCategoryIds(
                userId, kind, LocalDate.now(clock).minusDays(FREQUENT_DAYS), PageRequest.of(0, PICKER_SIZE + 1)));
        List<Category> all = categories.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .filter(category -> category.getKind() == kind && !category.isArchived())
                .toList();
        // Then the subcategories, which are more precise than their groups, then the groups
        all.stream().filter(category -> category.getParentId() != null).forEach(category -> ids.add(category.getId()));
        all.forEach(category -> ids.add(category.getId()));
        ids.remove(current);
        List<Button> buttons = ids.stream()
                .limit(PICKER_SIZE)
                .map(id -> all.stream()
                        .filter(category -> category.getId().equals(id))
                        .findFirst())
                .flatMap(Optional::stream)
                .map(category ->
                        Button.callback(category.getName(), "cs:" + transaction.getId() + ":" + category.getId()))
                .toList();
        List<Button> last = new ArrayList<>();
        transactionsUrl().ifPresent(url -> last.add(Button.link(texts.get(locale, "bot.button.otherCategory"), url)));
        last.add(Button.callback(texts.get(locale, "bot.button.back"), "b:" + transaction.getId()));
        return Keyboard.of(
                Stream.concat(pairs(buttons).stream(), Stream.of(last)).toList());
    }

    /** The other open accounts in the currency of the operation. */
    private Keyboard accountPicker(Transaction transaction, String action, Locale locale) {
        List<Button> buttons = openAccounts(transaction.getUserId()).stream()
                .filter(account ->
                        account.getCurrency().equals(transaction.getAccount().getCurrency()))
                .filter(account ->
                        !account.getId().equals(transaction.getAccount().getId()))
                .map(account ->
                        Button.callback(account.getName(), action + ":" + transaction.getId() + ":" + account.getId()))
                .toList();
        List<List<Button>> rows = new ArrayList<>(pairs(buttons));
        rows.add(List.of(Button.callback(texts.get(locale, "bot.button.back"), "b:" + transaction.getId())));
        return Keyboard.of(rows);
    }

    /** Asks which account the word means; the buttons repeat the message with the choices made so far. */
    private BotReply ambiguous(Mention mention, List<Long> resolved, List<Account> open, Locale locale) {
        String prefix = "pk:" + resolved.stream().map(id -> id + ":").collect(Collectors.joining());
        List<Button> buttons = open.stream()
                .filter(account -> mention.accountIds().contains(account.getId()))
                .map(account -> Button.callback(account.getName(), prefix + account.getId()))
                .toList();
        List<List<Button>> rows = new ArrayList<>(pairs(buttons));
        rows.add(List.of(Button.callback(texts.get(locale, "bot.button.cancel"), "pk-")));
        return new BotReply(texts.get(locale, "bot.entry.ambiguous", escape(mention.word())), Keyboard.of(rows));
    }

    private void update(
            Long userId,
            Transaction transaction,
            TransactionType type,
            Long accountId,
            Long toAccountId,
            Long categoryId) {
        boolean transfer = type == TransactionType.TRANSFER;
        transactionService.update(
                userId,
                transaction.getId(),
                new TransactionRequest(
                        type,
                        transaction.getDate(),
                        accountId,
                        transaction.getAmount(),
                        toAccountId,
                        transfer && transaction.getType() == TransactionType.TRANSFER
                                ? transaction.getToAmount()
                                : null,
                        transfer ? null : transaction.getOriginalAmount(),
                        transfer ? null : transaction.getOriginalCurrency(),
                        categoryId,
                        null,
                        !transfer && transaction.getPayee() != null
                                ? transaction.getPayee().getId()
                                : null,
                        null,
                        transaction.getComment(),
                        transaction.getTags().stream().map(Tag::getId).toList(),
                        transaction.isAdjustment()));
    }

    private Account sameCurrency(Transaction transaction, Long accountId) {
        return openAccounts(transaction.getUserId()).stream()
                .filter(account -> account.getId().equals(accountId))
                .filter(account ->
                        account.getCurrency().equals(transaction.getAccount().getCurrency()))
                .findFirst()
                .orElse(null);
    }

    private List<Account> openAccounts(Long userId) {
        return accounts.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .filter(account -> !account.isArchived())
                .toList();
    }

    /** The account chosen in the settings, otherwise the first card, the first cash account or the first account. */
    private static Account defaultAccount(TelegramLink link, List<Account> open) {
        return open.stream()
                .filter(account -> account.getId().equals(link.getQuickEntryAccountId()))
                .findFirst()
                .or(() -> open.stream()
                        .filter(account -> account.getType() == AccountType.CARD)
                        .findFirst())
                .or(() -> open.stream()
                        .filter(account -> account.getType() == AccountType.CASH)
                        .findFirst())
                .or(() -> open.stream().findFirst())
                .orElse(null);
    }

    private static Account find(List<Account> open, Long id) {
        return open.stream()
                .filter(account -> account.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private String amount(Transaction transaction, Locale locale) {
        return BotTexts.amount(
                transaction.getType(),
                transaction.getAmount(),
                transaction.getAccount().getCurrency(),
                locale);
    }

    private String path(Category category) {
        return categories.tree(category.getUserId()).path(category);
    }

    private Optional<String> transactionsUrl() {
        return BotTexts.buttonUrl(properties, "/transactions");
    }

    private static Long categoryId(Transaction transaction) {
        Category category = transaction.singleCategory();
        return category != null ? category.getId() : null;
    }

    private static CategoryKind kind(TransactionType type) {
        return CategoryKind.of(type);
    }

    private static Outcome reply(BotReply reply) {
        return new Outcome(reply, null);
    }

    private static <T> List<List<T>> pairs(List<T> items) {
        List<List<T>> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i += 2) {
            rows.add(items.subList(i, Math.min(i + 2, items.size())));
        }
        return rows;
    }
}
