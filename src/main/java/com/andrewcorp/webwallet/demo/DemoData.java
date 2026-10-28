package com.andrewcorp.webwallet.demo;

import com.andrewcorp.webwallet.budget.BudgetService;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalRequest;
import com.andrewcorp.webwallet.goal.GoalService;
import com.andrewcorp.webwallet.ledger.PaletteColor;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.account.dto.AccountRequest;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionService;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionRequest;
import com.andrewcorp.webwallet.planning.Frequency;
import com.andrewcorp.webwallet.planning.RecurringDtos.RecurringRequest;
import com.andrewcorp.webwallet.planning.RecurringService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The example data of a demo sandbox: a few accounts, four months of everyday operations, planned payments, this
 * month's budget and two goals. Dates are relative to today, so the overview always has a current month; amounts
 * come from a seeded random, so every sandbox looks the same but alive. Goes through the same services as the
 * clients, so balances, payees and budgets come out exactly as they would for a real user.
 */
@Component
public class DemoData {

    private static final int MONTHS_BACK = 3;
    private static final long SEED = 20261026L;
    private static final int BATCH = 100;

    private final AccountService accounts;
    private final TransactionService transactions;
    private final CategoryRepository categories;
    private final RecurringService recurring;
    private final BudgetService budget;
    private final GoalService goals;
    private final MessageSource messages;
    private final Clock clock;

    public DemoData(
            AccountService accounts,
            TransactionService transactions,
            CategoryRepository categories,
            RecurringService recurring,
            BudgetService budget,
            GoalService goals,
            MessageSource messages,
            Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.categories = categories;
        this.recurring = recurring;
        this.budget = budget;
        this.goals = goals;
        this.messages = messages;
        this.clock = clock;
    }

    /** Fills the account of a user who already has the default categories. */
    @Transactional
    public void fill(Long userId, Locale locale) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Europe/Kyiv")));
        Names names = new Names(userId, locale);
        Long card = account(userId, names.text("demo.account.card"), AccountType.CARD, "UAH", "0", null);
        Long cash = account(userId, names.text("demo.account.cash"), AccountType.CASH, "UAH", "1500", null);
        Long credit = account(userId, names.text("demo.account.credit"), AccountType.CREDIT_CARD, "UAH", "0", "20000");
        Long deposit = account(userId, names.text("demo.account.deposit"), AccountType.DEPOSIT, "UAH", "50000", null);
        Long usd = account(userId, names.text("demo.account.usd"), AccountType.CARD, "USD", "1200", null);
        Long debts = account(userId, names.text("demo.account.debts"), AccountType.DEBT, "UAH", "0", null);

        List<TransactionRequest> operations = new ArrayList<>();
        Random random = new Random(SEED);
        LocalDate start = today.minusMonths(MONTHS_BACK).withDayOfMonth(1);
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            operations.addAll(dayOperations(day, today, random, names, card, cash, credit, deposit));
        }
        operations.addAll(oneOffs(today, names, card, cash, usd, debts));
        for (int from = 0; from < operations.size(); from += BATCH) {
            transactions.createAll(userId, operations.subList(from, Math.min(from + BATCH, operations.size())));
        }

        plannedPayments(userId, today, names, card);
        budgetOfThisMonth(userId, today, names);
        goals.create(
                userId,
                new GoalRequest(
                        names.text("demo.goal.vacation"),
                        new BigDecimal("40000"),
                        "UAH",
                        today.plusMonths(6),
                        null,
                        new BigDecimal("15000"),
                        "plane",
                        PaletteColor.TEAL,
                        null,
                        0));
        goals.create(
                userId,
                new GoalRequest(
                        names.text("demo.goal.cushion"),
                        new BigDecimal("150000"),
                        "UAH",
                        null,
                        deposit,
                        null,
                        "shield",
                        PaletteColor.BLUE,
                        null,
                        1));
    }

    /** The operations of one day: salary twice a month, rent and utilities, groceries, coffee, transport, fun. */
    private List<TransactionRequest> dayOperations(
            LocalDate day,
            LocalDate today,
            Random random,
            Names names,
            Long card,
            Long cash,
            Long credit,
            Long deposit) {
        List<TransactionRequest> list = new ArrayList<>();
        int dom = day.getDayOfMonth();
        DayOfWeek dow = day.getDayOfWeek();
        if (dom == 5 || dom == 20) {
            list.add(income(day, card, "32000", names.category("salary"), names.payee("salary")));
        }
        if (dom == 1) {
            list.add(expense(day, card, "12000", names.category("rent"), names.payee("landlord"), null));
        }
        if (dom == 10) {
            list.add(expense(day, card, amount(random, 1800, 3200), names.category("utilities"), "ДТЕК", null));
            list.add(expense(day, card, "199", names.category("subscriptions"), "Netflix", null));
            list.add(expense(day, card, "149", names.category("subscriptions"), "Spotify", null));
        }
        if (dom == 12) {
            list.add(expense(day, card, "350", names.category("utilities"), "Київстар", null));
        }
        if (dom == 6) {
            list.add(transfer(day, card, deposit, "5000", null));
        }
        if (dom == 25) {
            list.add(transfer(day, card, credit, "4000", null));
        }
        if (dow == DayOfWeek.MONDAY || dow == DayOfWeek.THURSDAY || dow == DayOfWeek.SATURDAY) {
            String shop = random.nextBoolean() ? "Сільпо" : "АТБ";
            list.add(expense(
                    day,
                    random.nextInt(4) == 0 ? cash : card,
                    amount(random, 300, 1500),
                    names.category("groceries"),
                    shop,
                    null));
        }
        if (dow == DayOfWeek.FRIDAY) {
            list.add(expense(
                    day,
                    card,
                    amount(random, 400, 1200),
                    names.category("restaurants"),
                    "Milk Bar",
                    names.text("demo.comment.lunch")));
        }
        if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && random.nextInt(3) == 0) {
            list.add(expense(day, card, "8", names.category("publicTransport"), "Київ Цифровий", null));
        }
        if (random.nextInt(9) == 0) {
            list.add(expense(day, credit, amount(random, 150, 450), names.category("taxi"), "Uber", null));
        }
        if (dom == 15) {
            list.add(expense(day, card, amount(random, 1500, 2500), names.category("fuel"), "WOG", null));
        }
        if (dom == 18 && day.getMonthValue() % 2 == 0) {
            list.add(expense(day, credit, amount(random, 1200, 3500), names.category("clothing"), "Puma", null));
        }
        if (dom == 22) {
            list.add(expense(day, card, amount(random, 300, 900), names.category("health"), "Аптека", null));
        }
        if (dom == 8) {
            list.add(expense(day, card, amount(random, 200, 600), names.category("pets"), "Zoo Market", null));
        }
        return list;
    }

    /** A few operations that make the feed look lived in: a debt, a foreign purchase, a gift. */
    private List<TransactionRequest> oneOffs(LocalDate today, Names names, Long card, Long cash, Long usd, Long debts) {
        List<TransactionRequest> list = new ArrayList<>();
        String person = names.text("demo.person");
        list.add(new TransactionRequest(
                TransactionType.TRANSFER,
                today.minusDays(40),
                cash,
                new BigDecimal("2000"),
                debts,
                new BigDecimal("2000"),
                null,
                null,
                null,
                null,
                null,
                person,
                null,
                null,
                null));
        list.add(new TransactionRequest(
                TransactionType.TRANSFER,
                today.minusDays(12),
                debts,
                new BigDecimal("1000"),
                cash,
                new BigDecimal("1000"),
                null,
                null,
                null,
                null,
                null,
                person,
                null,
                null,
                null));
        list.add(new TransactionRequest(
                TransactionType.EXPENSE,
                today.minusDays(20),
                usd,
                new BigDecimal("49.99"),
                null,
                null,
                null,
                null,
                names.category("subscriptions"),
                null,
                null,
                "Steam",
                null,
                null,
                null));
        list.add(new TransactionRequest(
                TransactionType.EXPENSE,
                today.minusDays(33),
                card,
                new BigDecimal("1500"),
                null,
                null,
                null,
                null,
                names.category("donations"),
                null,
                null,
                null,
                names.text("demo.comment.gift"),
                null,
                null));
        list.add(new TransactionRequest(
                TransactionType.INCOME,
                today.minusDays(27),
                card,
                new BigDecimal("500"),
                null,
                null,
                null,
                null,
                names.category("interest"),
                null,
                null,
                null,
                null,
                null,
                null));
        return list;
    }

    /** Planned from next month on, so that nothing is overdue on the first open. */
    private void plannedPayments(Long userId, LocalDate today, Names names, Long card) {
        LocalDate nextMonth = today.plusMonths(1).withDayOfMonth(1);
        recurring.create(
                userId,
                new RecurringRequest(
                        names.text("demo.recurring.rent"),
                        TransactionType.EXPENSE,
                        card,
                        new BigDecimal("12000"),
                        null,
                        null,
                        names.category("rent"),
                        null,
                        names.payee("landlord"),
                        null,
                        Frequency.MONTHLY,
                        1,
                        nextMonth,
                        null,
                        false,
                        false,
                        10));
        recurring.create(
                userId,
                new RecurringRequest(
                        names.text("demo.recurring.utilities"),
                        TransactionType.EXPENSE,
                        card,
                        new BigDecimal("2500"),
                        null,
                        null,
                        names.category("utilities"),
                        null,
                        "ДТЕК",
                        null,
                        Frequency.MONTHLY,
                        1,
                        nextMonth.withDayOfMonth(10),
                        null,
                        false,
                        true,
                        30));
        recurring.create(
                userId,
                new RecurringRequest(
                        "Netflix",
                        TransactionType.EXPENSE,
                        card,
                        new BigDecimal("199"),
                        null,
                        null,
                        names.category("subscriptions"),
                        null,
                        "Netflix",
                        null,
                        Frequency.MONTHLY,
                        1,
                        nextMonth.withDayOfMonth(10),
                        null,
                        true,
                        false,
                        0));
        recurring.create(
                userId,
                new RecurringRequest(
                        names.text("demo.recurring.salary"),
                        TransactionType.INCOME,
                        card,
                        new BigDecimal("32000"),
                        null,
                        null,
                        names.category("salary"),
                        null,
                        names.payee("salary"),
                        null,
                        Frequency.MONTHLY,
                        1,
                        nextMonth.withDayOfMonth(5),
                        null,
                        false,
                        false,
                        10));
    }

    private void budgetOfThisMonth(Long userId, LocalDate today, Names names) {
        YearMonth month = YearMonth.from(today);
        Map<String, String> plan = Map.of(
                "groceries", "9000",
                "restaurants", "3000",
                "rent", "12000",
                "utilities", "3000",
                "publicTransport", "500",
                "taxi", "1500",
                "fuel", "2500",
                "subscriptions", "600",
                "clothing", "2000",
                "health", "1000");
        plan.forEach((key, amount) -> budget.assign(userId, month, names.category(key), new BigDecimal(amount)));
        // Expected income: the salary comes twice a month
        budget.assign(userId, month, names.category("salary"), new BigDecimal("64000"));
    }

    private Long account(Long userId, String name, AccountType type, String currency, String opening, String limit) {
        return accounts.create(
                        userId,
                        new AccountRequest(
                                name,
                                type,
                                currency,
                                type.inBudgetByDefault(),
                                null,
                                new BigDecimal(opening),
                                limit == null ? null : new BigDecimal(limit),
                                type == AccountType.DEPOSIT ? new BigDecimal("12") : null,
                                null))
                .id();
    }

    private static TransactionRequest expense(
            LocalDate date, Long accountId, String amount, Long categoryId, String payee, String comment) {
        return new TransactionRequest(
                TransactionType.EXPENSE,
                date,
                accountId,
                new BigDecimal(amount),
                null,
                null,
                null,
                null,
                categoryId,
                null,
                null,
                payee,
                comment,
                null,
                null);
    }

    private static TransactionRequest income(
            LocalDate date, Long accountId, String amount, Long categoryId, String payee) {
        return new TransactionRequest(
                TransactionType.INCOME,
                date,
                accountId,
                new BigDecimal(amount),
                null,
                null,
                null,
                null,
                categoryId,
                null,
                null,
                payee,
                null,
                null,
                null);
    }

    private static TransactionRequest transfer(LocalDate date, Long from, Long to, String amount, String comment) {
        return new TransactionRequest(
                TransactionType.TRANSFER,
                date,
                from,
                new BigDecimal(amount),
                to,
                new BigDecimal(amount),
                null,
                null,
                null,
                null,
                null,
                null,
                comment,
                null,
                null);
    }

    /** A whole amount between the bounds. */
    private static String amount(Random random, int min, int max) {
        return String.valueOf(min + random.nextInt(max - min + 1));
    }

    /** The texts and the default categories of the user, by their message keys. */
    private final class Names {
        private final Locale locale;
        private final Map<String, Long> categoryIds = new HashMap<>();

        Names(Long userId, Locale locale) {
            this.locale = locale;
            Map<String, Long> byName = new HashMap<>();
            for (Category category : categories.findByUserIdOrderBySortOrderAscNameAsc(userId)) {
                byName.putIfAbsent(category.getName(), category.getId());
            }
            for (String key : List.of(
                    "salary",
                    "rent",
                    "utilities",
                    "subscriptions",
                    "groceries",
                    "restaurants",
                    "publicTransport",
                    "taxi",
                    "fuel",
                    "clothing",
                    "health",
                    "pets",
                    "donations",
                    "interest")) {
                categoryIds.put(key, byName.get(text("category.default." + key)));
            }
        }

        String text(String key) {
            return messages.getMessage(key, null, locale);
        }

        Long category(String key) {
            return categoryIds.get(key);
        }

        String payee(String key) {
            return switch (key) {
                case "salary" -> locale.getLanguage().equals("uk") ? "Роботодавець" : "Employer";
                case "landlord" -> locale.getLanguage().equals("uk") ? "Орендодавець" : "Landlord";
                default -> key;
            };
        }
    }
}
