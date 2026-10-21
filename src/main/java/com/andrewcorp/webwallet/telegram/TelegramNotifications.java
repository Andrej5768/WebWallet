package com.andrewcorp.webwallet.telegram;

import static com.andrewcorp.webwallet.telegram.BotTexts.escape;

import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CategoryChange;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CompareResponse;
import com.andrewcorp.webwallet.analytics.AnalyticsService;
import com.andrewcorp.webwallet.bank.BankConnection;
import com.andrewcorp.webwallet.bank.BankConnectionRepository;
import com.andrewcorp.webwallet.bank.BankProvider;
import com.andrewcorp.webwallet.budget.BudgetDtos.CategoryRow;
import com.andrewcorp.webwallet.budget.BudgetDtos.MonthResponse;
import com.andrewcorp.webwallet.budget.BudgetService;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalResponse;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalStatus;
import com.andrewcorp.webwallet.goal.GoalService;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.notification.NotificationKind;
import com.andrewcorp.webwallet.notification.NotificationLog;
import com.andrewcorp.webwallet.notification.NotificationSettings;
import com.andrewcorp.webwallet.notification.NotificationSettingsService;
import com.andrewcorp.webwallet.notification.SpendingCharts;
import com.andrewcorp.webwallet.planning.ForecastDtos.ForecastDay;
import com.andrewcorp.webwallet.planning.ForecastService;
import com.andrewcorp.webwallet.planning.OccurrenceStatus;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceConfirmRequest;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceResponse;
import com.andrewcorp.webwallet.planning.RecurringService;
import com.andrewcorp.webwallet.report.ReportDtos.IncomeExpenseResponse;
import com.andrewcorp.webwallet.report.ReportService;
import com.andrewcorp.webwallet.tax.FopTaxService;
import com.andrewcorp.webwallet.tax.TaxDtos.FopQuarter;
import com.andrewcorp.webwallet.tax.TaxDtos.FopReport;
import com.andrewcorp.webwallet.telegram.TelegramApi.Button;
import com.andrewcorp.webwallet.telegram.TelegramApi.Keyboard;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notifications in Telegram. The reminders and reports go once a day at the hour the user chose; a bank that stopped
 * accepting the token is reported within minutes, but not at night. Every notification is sent once.
 */
@Component
public class TelegramNotifications {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifications.class);
    private static final LocalTime QUIET_FROM = LocalTime.of(22, 0);
    private static final LocalTime QUIET_TO = LocalTime.of(8, 0);
    private static final Duration LOG_RETENTION = Duration.ofDays(400);
    private static final int FORECAST_DAYS = 30;
    private static final int BUDGET_WARNING = 80;
    private static final int TOP_CHANGES = 2;
    private static final int CHART_CATEGORIES = 6;
    private static final int TAX_WARNING_DAYS = 7;

    private final TelegramClient client;
    private final TelegramLinkRepository links;
    private final NotificationSettingsService settingsService;
    private final NotificationLog notificationLog;
    private final RecurringService recurringService;
    private final BudgetService budgetService;
    private final FopTaxService fopTaxService;
    private final ForecastService forecastService;
    private final GoalService goalService;
    private final AnalyticsService analyticsService;
    private final ReportService reportService;
    private final BankConnectionRepository bankConnections;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final BotCommands commands;
    private final BotTexts texts;
    private final AppProperties properties;
    private final Clock clock;

    public TelegramNotifications(
            TelegramClient client,
            TelegramLinkRepository links,
            NotificationSettingsService settingsService,
            NotificationLog notificationLog,
            RecurringService recurringService,
            BudgetService budgetService,
            FopTaxService fopTaxService,
            ForecastService forecastService,
            GoalService goalService,
            AnalyticsService analyticsService,
            ReportService reportService,
            BankConnectionRepository bankConnections,
            AccountRepository accounts,
            UserRepository users,
            BotCommands commands,
            BotTexts texts,
            AppProperties properties,
            Clock clock) {
        this.client = client;
        this.links = links;
        this.settingsService = settingsService;
        this.notificationLog = notificationLog;
        this.recurringService = recurringService;
        this.budgetService = budgetService;
        this.fopTaxService = fopTaxService;
        this.forecastService = forecastService;
        this.goalService = goalService;
        this.analyticsService = analyticsService;
        this.reportService = reportService;
        this.bankConnections = bankConnections;
        this.accounts = accounts;
        this.users = users;
        this.commands = commands;
        this.texts = texts;
        this.properties = properties;
        this.clock = clock;
    }

    /** A notification: its text, buttons and, for a report, a chart. */
    record Notice(NotificationKind kind, String ref, String html, Keyboard keyboard, byte[] chart) {

        static Notice text(NotificationKind kind, String ref, String html, Keyboard keyboard) {
            return new Notice(kind, ref, html, keyboard, null);
        }
    }

    /** Every hour: the daily notifications of the users who chose this hour. */
    @Scheduled(cron = "${app.notifications.daily-cron:0 0 * * * *}", zone = "Europe/Kyiv")
    public void daily() {
        if (!properties.telegram().enabled()) {
            return;
        }
        int hour = ZonedDateTime.now(clock.withZone(Zones.KYIV)).getHour();
        for (TelegramLink link : links.findAll()) {
            NotificationSettings settings = settingsService.of(link.getUserId());
            if (settings.getNotifyHour() == hour) {
                sendDaily(link, settings);
            }
        }
    }

    /** Every few minutes: banks that no longer accept the token. */
    @Scheduled(cron = "${app.notifications.banks-cron:0 */10 * * * *}", zone = "Europe/Kyiv")
    public void banks() {
        if (!properties.telegram().enabled()) {
            return;
        }
        LocalTime now = LocalTime.now(clock.withZone(Zones.KYIV));
        boolean quiet = !now.isBefore(QUIET_FROM) || now.isBefore(QUIET_TO);
        for (TelegramLink link : links.findAll()) {
            Long userId = link.getUserId();
            Optional<BankConnection> connection =
                    bankConnections.findByUserIdAndProvider(userId, BankProvider.MONOBANK);
            String ref = connection.map(found -> "monobank:" + found.getId()).orElse(null);
            if (connection.isEmpty()
                    || !ErrorCode.INVALID_EXTERNAL_TOKEN
                            .name()
                            .equals(connection.get().getLastError())) {
                if (ref != null) {
                    // Solved: a new problem is reported again
                    notificationLog.forget(userId, NotificationKind.BANKS, ref);
                }
                continue;
            }
            if (quiet || !settingsService.of(userId).isOn(NotificationKind.BANKS)) {
                continue;
            }
            Locale locale = locale(userId);
            deliver(link, Notice.text(NotificationKind.BANKS, ref, texts.get(locale, "notify.bank"), Keyboard.NONE));
        }
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Europe/Kyiv")
    public void cleanup() {
        notificationLog.deleteSentBefore(clock.instant().minus(LOG_RETENTION));
    }

    /** The daily notifications of one user that are switched on; a failing one does not stop the others. */
    void sendDaily(TelegramLink link, NotificationSettings settings) {
        Long userId = link.getUserId();
        Locale locale = locale(userId);
        LocalDate today = LocalDate.now(clock);
        List<NotificationKind> kinds = List.of(
                NotificationKind.RECURRING,
                NotificationKind.BUDGETS,
                NotificationKind.TAXES,
                NotificationKind.FORECAST,
                NotificationKind.GOALS,
                NotificationKind.WEEKLY,
                NotificationKind.MONTHLY);
        for (NotificationKind kind : kinds) {
            if (!settings.isOn(kind)) {
                continue;
            }
            try {
                for (Notice notice : notices(kind, userId, today, locale)) {
                    deliver(link, notice);
                }
            } catch (RuntimeException e) {
                log.error("Telegram notifications {} of user {} failed", kind, userId, e);
            }
        }
    }

    private List<Notice> notices(NotificationKind kind, Long userId, LocalDate today, Locale locale) {
        return switch (kind) {
            case RECURRING -> recurring(userId, today, locale);
            case BUDGETS -> budgets(userId, today, locale);
            case TAXES -> taxes(userId, today, locale);
            case FORECAST -> forecast(userId, today, locale);
            case GOALS -> goals(userId, today, locale);
            case WEEKLY -> weekly(userId, today, locale);
            case MONTHLY -> monthly(userId, today, locale);
            case BANKS -> List.of();
        };
    }

    /** Sends the notice unless it was sent before. */
    private void deliver(TelegramLink link, Notice notice) {
        Long userId = link.getUserId();
        if (notificationLog.sent(userId, notice.kind(), notice.ref())) {
            return;
        }
        if (notice.chart() != null) {
            client.sendPhoto(link.getChatId(), notice.chart(), notice.html(), notice.keyboard());
        } else {
            client.sendMessage(link.getChatId(), notice.html(), notice.keyboard(), null);
        }
        notificationLog.markSent(userId, notice.kind(), notice.ref());
    }

    // Recurring payments

    /**
     * Payments due tomorrow and today that are not linked to a transaction yet. A payment created automatically
     * needs no reminder on its day.
     */
    List<Notice> recurring(Long userId, LocalDate today, Locale locale) {
        List<Notice> notices = new ArrayList<>();
        for (OccurrenceResponse occurrence : recurringService.occurrences(userId, today, today.plusDays(1))) {
            if (occurrence.status() != OccurrenceStatus.PENDING) {
                continue;
            }
            boolean tomorrow = occurrence.date().isAfter(today);
            if (!tomorrow && occurrence.autoCreate()) {
                continue;
            }
            String account = accounts.findById(occurrence.accountId())
                    .map(Account::getName)
                    .orElse("");
            String html = texts.get(
                    locale,
                    tomorrow ? "notify.recurring.tomorrow" : "notify.recurring.today",
                    escape(occurrence.name()),
                    escape(account),
                    amount(occurrence, locale));
            Button planned = Button.callback(texts.get(locale, "notify.button.planned"), "pl");
            List<Button> row = tomorrow
                    ? List.of(planned)
                    : List.of(
                            Button.callback(
                                    texts.get(locale, "notify.button.paid"),
                                    "rp:" + occurrence.recurringId() + ":" + occurrence.date()),
                            planned);
            String ref = occurrence.recurringId() + ":" + occurrence.date() + (tomorrow ? ":before" : ":due");
            notices.add(Notice.text(NotificationKind.RECURRING, ref, html, Keyboard.of(List.of(row))));
        }
        return notices;
    }

    /** «Сплачено» under a reminder: records the payment as the recurring payment describes it. */
    BotReply markPaid(Long userId, Locale locale, Long recurringId, LocalDate date) {
        OccurrenceResponse paid;
        try {
            paid = recurringService.confirm(userId, recurringId, date, new OccurrenceConfirmRequest(null, null, null));
        } catch (ApiException e) {
            return BotReply.text(texts.get(locale, "notify.recurring.alreadyPaid"));
        }
        return new BotReply(
                texts.get(locale, "notify.recurring.paid", escape(paid.name()), amount(paid, locale)),
                Keyboard.of(List.of(List.of(Button.callback(texts.get(locale, "notify.button.planned"), "pl")))));
    }

    /** The list of /planned, for the button under a reminder. */
    String planned(Long userId, Locale locale) {
        return commands.planned(userId, locale);
    }

    private static String amount(OccurrenceResponse occurrence, Locale locale) {
        return occurrence.type() == TransactionType.EXPENSE
                ? BotTexts.money(occurrence.amount().negate(), occurrence.currency(), locale)
                : occurrence.type() == TransactionType.INCOME
                        ? BotTexts.signedMoney(occurrence.amount(), occurrence.currency(), locale)
                        : BotTexts.money(occurrence.amount(), occurrence.currency(), locale);
    }

    // Budgets

    /** Envelopes whose spending of the month reached 80 % or 100 % of what is assigned to them. */
    List<Notice> budgets(Long userId, LocalDate today, Locale locale) {
        YearMonth month = YearMonth.from(today);
        MonthResponse budget = budgetService.month(userId, month);
        int daysLeft = month.lengthOfMonth() - today.getDayOfMonth();
        List<Notice> notices = new ArrayList<>();
        for (CategoryRow row : flatten(budget.categories())) {
            if (row.archived() || row.ownAssigned().signum() <= 0) {
                continue;
            }
            BigDecimal spent = row.ownActivity().negate();
            int percent = spent.multiply(BigDecimal.valueOf(100))
                    .divide(row.ownAssigned(), 0, RoundingMode.FLOOR)
                    .intValue();
            int threshold = percent >= 100 ? 100 : percent >= BUDGET_WARNING ? BUDGET_WARNING : 0;
            if (threshold == 0) {
                continue;
            }
            String ref = month + ":" + row.categoryId() + ":";
            if (threshold == 100) {
                // Over the budget at once: the warning would come too late
                notificationLog.markSent(userId, NotificationKind.BUDGETS, ref + BUDGET_WARNING);
            }
            String html = texts.get(
                    locale,
                    "notify.budget",
                    escape(row.name()),
                    percent,
                    BotTexts.wholeMoney(spent, budget.currency(), locale),
                    BotTexts.wholeMoney(row.ownAssigned(), budget.currency(), locale),
                    daysLeft);
            notices.add(Notice.text(NotificationKind.BUDGETS, ref + threshold, html, openKeyboard(locale, "/budget")));
        }
        return notices;
    }

    private static List<CategoryRow> flatten(List<CategoryRow> rows) {
        return rows.stream()
                .flatMap(row -> Stream.concat(Stream.of(row), flatten(row.children()).stream()))
                .toList();
    }

    // Taxes of the sole proprietor

    /** Payments and declarations of the sole proprietor due in 7 days or tomorrow. */
    List<Notice> taxes(Long userId, LocalDate today, Locale locale) {
        List<Notice> notices = new ArrayList<>();
        for (int year = today.getYear() - 1; year <= today.getYear(); year++) {
            FopReport report = fopTaxService.report(userId, year);
            if (report.fopAccountIds().isEmpty()) {
                return notices;
            }
            for (FopQuarter quarter : report.quarters()) {
                if (quarter.paid()) {
                    continue;
                }
                String number = BotCommands.roman(quarter.quarter());
                tax(
                        notices,
                        today,
                        locale,
                        year,
                        quarter,
                        "singleTax",
                        quarter.taxDue(),
                        quarter.singleTax().add(quarter.military()),
                        number);
                tax(notices, today, locale, year, quarter, "esv", quarter.esvDue(), quarter.esv(), number);
                tax(notices, today, locale, year, quarter, "declaration", quarter.declarationDue(), null, number);
            }
        }
        return notices;
    }

    private void tax(
            List<Notice> notices,
            LocalDate today,
            Locale locale,
            int year,
            FopQuarter quarter,
            String what,
            LocalDate due,
            BigDecimal amount,
            String number) {
        if (due == null || (amount != null && amount.signum() <= 0)) {
            return;
        }
        boolean week = due.equals(today.plusDays(TAX_WARNING_DAYS));
        boolean tomorrow = due.equals(today.plusDays(1));
        if (!week && !tomorrow) {
            return;
        }
        String subject = texts.get(locale, "notify.tax." + what, number);
        String title = texts.get(locale, week ? "notify.tax.week" : "notify.tax.tomorrow", subject);
        String dueText = amount != null
                ? texts.get(locale, "notify.tax.due", BotTexts.date(due, locale), BotTexts.money(amount, "UAH", locale))
                : texts.get(locale, "notify.tax.dueDate", BotTexts.date(due, locale));
        String ref = year + ":" + quarter.quarter() + ":" + what + ":" + (week ? TAX_WARNING_DAYS : 1);
        notices.add(Notice.text(
                NotificationKind.TAXES, ref, "<b>" + title + "</b>\n" + dueText, openKeyboard(locale, "/taxes")));
    }

    // Forecast

    /** The first day of the next 30 the forecast balance is below zero; again only when the day or the sum changes. */
    List<Notice> forecast(Long userId, LocalDate today, Locale locale) {
        var forecast = forecastService.forecast(userId, today.plusDays(FORECAST_DAYS));
        Optional<ForecastDay> negative = forecast.days().stream()
                .filter(day -> !day.actual() && day.balance().signum() < 0)
                .findFirst();
        if (negative.isEmpty()) {
            return List.of();
        }
        ForecastDay day = negative.get();
        BigDecimal rounded = day.balance().divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
        String html = texts.get(
                locale,
                "notify.forecast",
                BotTexts.date(day.date(), locale),
                BotTexts.money(day.balance(), forecast.currency(), locale));
        return List.of(
                Notice.text(NotificationKind.FORECAST, day.date() + ":" + rounded, html, openKeyboard(locale, "/")));
    }

    // Goals

    /** Goals reached today or yesterday. */
    List<Notice> goals(Long userId, LocalDate today, Locale locale) {
        List<Notice> notices = new ArrayList<>();
        for (GoalResponse goal : goalService.list(userId).goals()) {
            if (goal.status() != GoalStatus.ACHIEVED
                    || goal.achievedOn() == null
                    || goal.achievedOn().isBefore(today.minusDays(1))) {
                continue;
            }
            String html = texts.get(
                    locale,
                    "notify.goal",
                    escape(goal.name()),
                    BotTexts.wholeMoney(goal.saved(), goal.currency(), locale),
                    BotTexts.wholeMoney(goal.targetAmount(), goal.currency(), locale));
            notices.add(Notice.text(
                    NotificationKind.GOALS, String.valueOf(goal.id()), html, openKeyboard(locale, "/goals")));
        }
        return notices;
    }

    // Spending reports

    /**
     * On Mondays, except in the first week of a month: the spending from the 1st to yesterday against the same days
     * of the previous month, with the categories that changed most and a chart.
     */
    List<Notice> weekly(Long userId, LocalDate today, Locale locale) {
        if (today.getDayOfWeek() != DayOfWeek.MONDAY || today.getDayOfMonth() <= 7) {
            return List.of();
        }
        LocalDate to = today.minusDays(1);
        LocalDate from = to.withDayOfMonth(1);
        LocalDate previousFrom = from.minusMonths(1);
        LocalDate previousTo = previousFrom.withDayOfMonth(
                Math.min(to.getDayOfMonth(), YearMonth.from(previousFrom).lengthOfMonth()));
        CompareResponse compare = analyticsService.compare(userId, from, to, previousFrom, previousTo);
        if (compare.totalA().signum() == 0 && compare.totalB().signum() == 0) {
            return List.of();
        }
        String currency = compare.currency();
        String period = period(from, to, locale);
        String previousPeriod = period(previousFrom, previousTo, locale);
        String total = BotTexts.wholeMoney(compare.totalA(), currency, locale);
        String summary;
        if (compare.totalB().signum() == 0) {
            summary = texts.get(locale, "notify.weekly.noPrevious", total, previousPeriod);
        } else if (compare.change().signum() == 0) {
            summary = texts.get(locale, "notify.weekly.same", total, previousPeriod);
        } else {
            summary = texts.get(
                    locale,
                    compare.change().signum() > 0 ? "notify.weekly.more" : "notify.weekly.less",
                    total,
                    percent(compare.changeShare()),
                    previousPeriod,
                    BotTexts.wholeMoney(compare.totalB(), currency, locale));
        }
        StringBuilder html = new StringBuilder(texts.get(locale, "notify.weekly.title", period))
                .append('\n')
                .append(summary);
        changes(html, compare, locale);
        BigDecimal assigned = budgetService.month(userId, YearMonth.from(from)).assigned();
        if (assigned.signum() > 0) {
            int spent = compare.totalA()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(assigned, 0, RoundingMode.HALF_UP)
                    .intValue();
            int passed = to.getDayOfMonth() * 100 / YearMonth.from(from).lengthOfMonth();
            html.append("\n\n<i>")
                    .append(texts.get(locale, "notify.weekly.budget", spent, passed))
                    .append("</i>");
        }
        byte[] chart = SpendingCharts.cumulative(
                texts.get(locale, "notify.weekly.chart") + ", " + BotTexts.symbol(currency),
                BotTexts.month(YearMonth.from(from), locale),
                BotTexts.month(YearMonth.from(previousFrom), locale),
                compare.days().stream()
                        .map(day -> new SpendingCharts.Day(day.day(), day.a(), day.b()))
                        .toList(),
                locale);
        return List.of(new Notice(
                NotificationKind.WEEKLY, today.toString(), html.toString(), openKeyboard(locale, "/reports"), chart));
    }

    /** On the 1st: the previous month against the month before it, with a chart of the categories. */
    List<Notice> monthly(Long userId, LocalDate today, Locale locale) {
        if (today.getDayOfMonth() != 1) {
            return List.of();
        }
        YearMonth month = YearMonth.from(today).minusMonths(1);
        YearMonth before = month.minusMonths(1);
        CompareResponse compare = analyticsService.compare(
                userId, month.atDay(1), month.atEndOfMonth(), before.atDay(1), before.atEndOfMonth());
        IncomeExpenseResponse current = reportService.incomeExpense(userId, month.atDay(1), month.atEndOfMonth());
        IncomeExpenseResponse previous = reportService.incomeExpense(userId, before.atDay(1), before.atEndOfMonth());
        if (current.income().signum() == 0 && current.expense().signum() == 0) {
            return List.of();
        }
        String currency = current.currency();
        StringBuilder html =
                new StringBuilder(texts.get(locale, "notify.monthly.title", BotTexts.month(month, locale)));
        html.append("\n")
                .append(texts.get(locale, "notify.monthly.income"))
                .append(" — ")
                .append(withChange(current.income(), previous.income(), currency, locale));
        html.append("\n")
                .append(texts.get(locale, "notify.monthly.expense"))
                .append(" — ")
                .append(withChange(current.expense(), previous.expense(), currency, locale));
        html.append("\n")
                .append(texts.get(locale, "notify.monthly.saved"))
                .append(" — ")
                .append(BotTexts.wholeMoney(current.net(), currency, locale));
        if (current.savingsRate() != null) {
            html.append(" · ").append(percent(current.savingsRate())).append(" %");
        }
        changes(html, compare, locale);

        List<CategoryChange> categories = compare.categories().stream()
                .sorted((a, b) -> b.a().compareTo(a.a()))
                .toList();
        List<SpendingCharts.Bar> bars = new ArrayList<>();
        int shown = categories.size() > CHART_CATEGORIES ? CHART_CATEGORIES - 1 : categories.size();
        for (CategoryChange category : categories.subList(0, shown)) {
            bars.add(new SpendingCharts.Bar(name(category, locale), category.a(), category.b()));
        }
        if (shown < categories.size()) {
            List<CategoryChange> rest = categories.subList(shown, categories.size());
            bars.add(new SpendingCharts.Bar(
                    texts.get(locale, "bot.other"),
                    rest.stream().map(CategoryChange::a).reduce(BigDecimal.ZERO, BigDecimal::add),
                    rest.stream().map(CategoryChange::b).reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        byte[] chart = bars.isEmpty()
                ? null
                : SpendingCharts.categories(
                        texts.get(locale, "notify.monthly.chart") + ", " + BotTexts.symbol(compare.currency()),
                        BotTexts.month(month, locale),
                        BotTexts.month(before, locale),
                        bars,
                        locale);
        return List.of(new Notice(
                NotificationKind.MONTHLY, month.toString(), html.toString(), openKeyboard(locale, "/reports"), chart));
    }

    /** The categories that grew and shrank most, two of each. */
    private void changes(StringBuilder html, CompareResponse compare, Locale locale) {
        List<CategoryChange> more = compare.categories().stream()
                .filter(change -> change.change().signum() > 0)
                .limit(TOP_CHANGES)
                .toList();
        List<CategoryChange> less = compare.categories().stream()
                .filter(change -> change.change().signum() < 0)
                .limit(TOP_CHANGES)
                .toList();
        changeGroup(html, "notify.change.more", more, compare.currency(), locale);
        changeGroup(html, "notify.change.less", less, compare.currency(), locale);
    }

    private void changeGroup(
            StringBuilder html, String titleKey, List<CategoryChange> changes, String currency, Locale locale) {
        if (changes.isEmpty()) {
            return;
        }
        html.append("\n\n<i>").append(texts.get(locale, titleKey)).append("</i>");
        for (CategoryChange change : changes) {
            html.append('\n')
                    .append(escape(name(change, locale)))
                    .append(" — ")
                    .append(change.change().signum() > 0 ? "+" : "")
                    .append(BotTexts.wholeMoney(change.change(), currency, locale));
            if (change.changeShare() != null) {
                html.append(" · ")
                        .append(change.changeShare().signum() > 0 ? "+" : "−")
                        .append(percent(change.changeShare()))
                        .append(" %");
            }
        }
    }

    private String withChange(BigDecimal current, BigDecimal previous, String currency, Locale locale) {
        String amount = BotTexts.wholeMoney(current, currency, locale);
        if (previous.signum() == 0) {
            return amount;
        }
        BigDecimal share = current.subtract(previous).divide(previous, 4, RoundingMode.HALF_UP);
        return amount + " · " + (share.signum() > 0 ? "+" : share.signum() < 0 ? "−" : "") + percent(share) + " %";
    }

    private String name(CategoryChange change, Locale locale) {
        return change.name() != null ? change.name() : texts.get(locale, "bot.uncategorized");
    }

    /** E.g. {@code 1–19 жовтня}. */
    private static String period(LocalDate from, LocalDate to, Locale locale) {
        String end = BotTexts.date(to, locale);
        return from.equals(to) ? end : from.getDayOfMonth() + "–" + end;
    }

    /** A share as whole percent without a sign. */
    private static int percent(BigDecimal share) {
        return share.abs()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private Keyboard openKeyboard(Locale locale, String path) {
        String url = properties.frontendUrl().replaceAll("/+$", "") + path;
        // Telegram accepts only public links on buttons
        return url.startsWith("https://")
                ? Keyboard.of(List.of(List.of(Button.link(texts.get(locale, "notify.button.open"), url))))
                : Keyboard.NONE;
    }

    private Locale locale(Long userId) {
        return BotTexts.locale(users.findById(userId).map(User::getLocale).orElse(null));
    }
}
