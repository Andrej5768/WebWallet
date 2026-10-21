package com.andrewcorp.webwallet.telegram;

import static com.andrewcorp.webwallet.telegram.BotTexts.escape;

import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CategoryChange;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CompareResponse;
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
import com.andrewcorp.webwallet.notification.NotificationKind;
import com.andrewcorp.webwallet.notification.NotificationLog;
import com.andrewcorp.webwallet.notification.NotificationSettings;
import com.andrewcorp.webwallet.notification.NotificationSettingsService;
import com.andrewcorp.webwallet.notification.SpendingCharts;
import com.andrewcorp.webwallet.notification.SpendingReports;
import com.andrewcorp.webwallet.planning.ForecastDtos.ForecastDay;
import com.andrewcorp.webwallet.planning.ForecastService;
import com.andrewcorp.webwallet.planning.OccurrenceStatus;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceConfirmRequest;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceResponse;
import com.andrewcorp.webwallet.planning.RecurringService;
import com.andrewcorp.webwallet.report.ReportDtos.IncomeExpenseResponse;
import com.andrewcorp.webwallet.tax.FopTaxService;
import com.andrewcorp.webwallet.tax.TaxDtos.FopDeadline;
import com.andrewcorp.webwallet.telegram.TelegramApi.Button;
import com.andrewcorp.webwallet.telegram.TelegramApi.Keyboard;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
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
    private static final int BUDGET_WARNING = 80;
    private static final int TAX_WARNING_DAYS = 7;

    private final TelegramClient client;
    private final TelegramLinkService linkService;
    private final NotificationSettingsService settingsService;
    private final NotificationLog notificationLog;
    private final RecurringService recurringService;
    private final BudgetService budgetService;
    private final FopTaxService fopTaxService;
    private final ForecastService forecastService;
    private final GoalService goalService;
    private final SpendingReports reports;
    private final BankConnectionRepository bankConnections;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final BotCommands commands;
    private final BotTexts texts;
    private final AppProperties properties;
    private final Clock clock;

    public TelegramNotifications(
            TelegramClient client,
            TelegramLinkService linkService,
            NotificationSettingsService settingsService,
            NotificationLog notificationLog,
            RecurringService recurringService,
            BudgetService budgetService,
            FopTaxService fopTaxService,
            ForecastService forecastService,
            GoalService goalService,
            SpendingReports reports,
            BankConnectionRepository bankConnections,
            AccountRepository accounts,
            UserRepository users,
            BotCommands commands,
            BotTexts texts,
            AppProperties properties,
            Clock clock) {
        this.client = client;
        this.linkService = linkService;
        this.settingsService = settingsService;
        this.notificationLog = notificationLog;
        this.recurringService = recurringService;
        this.budgetService = budgetService;
        this.fopTaxService = fopTaxService;
        this.forecastService = forecastService;
        this.goalService = goalService;
        this.reports = reports;
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
        for (TelegramLink link : linkService.activeLinks()) {
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
        for (TelegramLink link : linkService.activeLinks()) {
            try {
                bankProblem(link, quiet);
            } catch (RuntimeException e) {
                log.error("Telegram bank notification of user {} failed", link.getUserId(), e);
            }
        }
    }

    private void bankProblem(TelegramLink link, boolean quiet) {
        {
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
                return;
            }
            if (quiet || !settingsService.of(userId).isOn(NotificationKind.BANKS)) {
                return;
            }
            Locale locale = linkService.locale(userId);
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
        Locale locale = linkService.locale(userId);
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
            case BANKS, EMAIL_WEEKLY, EMAIL_MONTHLY -> List.of();
        };
    }

    /** Sends the notice unless it was sent before; a chat the user blocked or deleted is disconnected. */
    private void deliver(TelegramLink link, Notice notice) {
        Long userId = link.getUserId();
        if (notificationLog.sent(userId, notice.kind(), notice.ref())) {
            return;
        }
        try {
            if (notice.chart() != null) {
                client.sendPhoto(link.getChatId(), notice.chart(), notice.html(), notice.keyboard());
            } else {
                client.sendMessage(link.getChatId(), notice.html(), notice.keyboard(), null);
            }
        } catch (TelegramClient.TelegramException e) {
            if (e.chatGone()) {
                log.info("Telegram chat of user {} is gone; disconnected", userId);
                linkService.unlinkChat(link.getChatId());
                return;
            }
            throw e;
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
        return BotTexts.amount(occurrence.type(), occurrence.amount(), occurrence.currency(), locale);
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
        for (FopDeadline deadline :
                fopTaxService.deadlines(userId, today.plusDays(1), today.plusDays(TAX_WARNING_DAYS))) {
            boolean week = deadline.date().equals(today.plusDays(TAX_WARNING_DAYS));
            if (!week && !deadline.date().equals(today.plusDays(1))) {
                continue;
            }
            String what = switch (deadline.kind()) {
                case SINGLE_TAX -> "singleTax";
                case ESV -> "esv";
                case DECLARATION -> "declaration";
            };
            String subject = texts.get(locale, "notify.tax." + what, BotCommands.roman(deadline.quarter()));
            String title = texts.get(locale, week ? "notify.tax.week" : "notify.tax.tomorrow", subject);
            String due = BotTexts.date(deadline.date(), locale);
            String dueText = deadline.amount() != null
                    ? texts.get(locale, "notify.tax.due", due, BotTexts.money(deadline.amount(), "UAH", locale))
                    : texts.get(locale, "notify.tax.dueDate", due);
            String ref = deadline.year() + ":" + deadline.quarter() + ":" + what + ":" + (week ? TAX_WARNING_DAYS : 1);
            notices.add(Notice.text(
                    NotificationKind.TAXES, ref, "<b>" + title + "</b>\n" + dueText, openKeyboard(locale, "/taxes")));
        }
        return notices;
    }

    // Forecast

    /** The first day of the next 30 the forecast balance is below zero; again only when the day or the sum changes. */
    List<Notice> forecast(Long userId, LocalDate today, Locale locale) {
        // The same days as /planned, so that both tell the same
        LocalDate last = today.plusDays(BotCommands.PLANNED_DAYS - 1);
        Optional<ForecastDay> negative = forecastService.firstNegativeDay(userId, last);
        if (negative.isEmpty()) {
            return List.of();
        }
        ForecastDay day = negative.get();
        BigDecimal rounded = day.balance().divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
        String html = texts.get(
                locale,
                "notify.forecast",
                BotTexts.date(day.date(), locale),
                BotTexts.money(day.balance(), userCurrency(userId), locale));
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

    /** The weekly report with its chart; see {@link SpendingReports#weekly}. */
    List<Notice> weekly(Long userId, LocalDate today, Locale locale) {
        return reports.weekly(userId, today).map(weekly -> weeklyNotice(weekly, locale)).stream()
                .toList();
    }

    private Notice weeklyNotice(SpendingReports.Weekly weekly, Locale locale) {
        CompareResponse compare = weekly.compare();
        String currency = compare.currency();
        String period = BotTexts.period(weekly.from(), weekly.to(), locale);
        String previousPeriod = BotTexts.period(weekly.previousFrom(), weekly.previousTo(), locale);
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
                    SpendingReports.percent(compare.changeShare()),
                    previousPeriod,
                    BotTexts.wholeMoney(compare.totalB(), currency, locale));
        }
        StringBuilder html = new StringBuilder(texts.get(locale, "notify.weekly.title", period))
                .append('\n')
                .append(summary);
        changeGroup(html, "notify.change.more", weekly.more(), currency, locale);
        changeGroup(html, "notify.change.less", weekly.less(), currency, locale);
        if (weekly.budgetUsed() != null) {
            html.append("\n\n<i>")
                    .append(texts.get(locale, "notify.weekly.budget", weekly.budgetUsed(), weekly.monthPassed()))
                    .append("</i>");
        }
        byte[] chart = SpendingCharts.cumulative(
                texts.get(locale, "notify.weekly.chart") + ", " + BotTexts.symbol(currency),
                BotTexts.month(YearMonth.from(weekly.from()), locale),
                BotTexts.month(YearMonth.from(weekly.previousFrom()), locale),
                weekly.days(),
                locale);
        return new Notice(
                NotificationKind.WEEKLY,
                weekly.monday().toString(),
                html.toString(),
                openKeyboard(locale, "/reports"),
                chart);
    }

    /** The monthly report with a chart of the categories; see {@link SpendingReports#monthly}. */
    List<Notice> monthly(Long userId, LocalDate today, Locale locale) {
        return reports.monthly(userId, today).map(monthly -> monthlyNotice(monthly, locale)).stream()
                .toList();
    }

    private Notice monthlyNotice(SpendingReports.Monthly monthly, Locale locale) {
        IncomeExpenseResponse current = monthly.current();
        IncomeExpenseResponse previous = monthly.previous();
        String currency = current.currency();
        StringBuilder html =
                new StringBuilder(texts.get(locale, "notify.monthly.title", BotTexts.month(monthly.month(), locale)));
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
            html.append(" · ")
                    .append(SpendingReports.percent(current.savingsRate()))
                    .append(" %");
        }
        changeGroup(html, "notify.change.more", monthly.more(), currency, locale);
        changeGroup(html, "notify.change.less", monthly.less(), currency, locale);
        List<SpendingCharts.Bar> bars =
                monthly.bars(texts.get(locale, "bot.other"), texts.get(locale, "bot.uncategorized"));
        byte[] chart = bars.isEmpty()
                ? null
                : SpendingCharts.categories(
                        texts.get(locale, "notify.monthly.chart") + ", " + BotTexts.symbol(currency),
                        BotTexts.month(monthly.month(), locale),
                        BotTexts.month(monthly.before(), locale),
                        bars,
                        locale);
        return new Notice(
                NotificationKind.MONTHLY,
                monthly.month().toString(),
                html.toString(),
                openKeyboard(locale, "/reports"),
                chart);
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
                        .append(SpendingReports.percent(change.changeShare()))
                        .append(" %");
            }
        }
    }

    private String withChange(BigDecimal current, BigDecimal previous, String currency, Locale locale) {
        String amount = BotTexts.wholeMoney(current, currency, locale);
        BigDecimal share = SpendingReports.change(current, previous);
        if (share == null) {
            return amount;
        }
        return amount + " · " + (share.signum() > 0 ? "+" : share.signum() < 0 ? "−" : "")
                + SpendingReports.percent(share) + " %";
    }

    private String name(CategoryChange change, Locale locale) {
        return change.name() != null ? change.name() : texts.get(locale, "bot.uncategorized");
    }

    private Keyboard openKeyboard(Locale locale, String path) {
        return BotTexts.buttonUrl(properties, path)
                .map(url -> Keyboard.of(List.of(List.of(Button.link(texts.get(locale, "notify.button.open"), url)))))
                .orElse(Keyboard.NONE);
    }

    private String userCurrency(Long userId) {
        return users.findById(userId).map(User::getBaseCurrency).orElse("UAH");
    }
}
