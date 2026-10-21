package com.andrewcorp.webwallet.notification;

import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CategoryChange;
import com.andrewcorp.webwallet.analytics.AnalyticsDtos.CompareResponse;
import com.andrewcorp.webwallet.budget.BudgetDtos.CategoryRow;
import com.andrewcorp.webwallet.budget.BudgetService;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalResponse;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalStatus;
import com.andrewcorp.webwallet.goal.GoalService;
import com.andrewcorp.webwallet.notification.DigestMail.Line;
import com.andrewcorp.webwallet.planning.ForecastDtos.ForecastDay;
import com.andrewcorp.webwallet.planning.ForecastService;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceResponse;
import com.andrewcorp.webwallet.planning.RecurringService;
import com.andrewcorp.webwallet.report.ReportDtos.IncomeExpenseResponse;
import com.andrewcorp.webwallet.telegram.BotTexts;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The email digest: the weekly and the monthly spending report by email, at the hour of the notifications of the
 * user. It is on by default for every confirmed address; each email can unsubscribe in one click.
 */
@Component
public class EmailDigests {

    private static final Logger log = LoggerFactory.getLogger(EmailDigests.class);
    private static final String CHART = "chart";
    private static final int BUDGET_WARNING = 80;
    private static final int PAYMENT_DAYS = 7;
    private static final int MAX_PAYMENTS = 8;
    private static final int FORECAST_DAYS = 30;
    private static final int MAX_GOALS = 3;

    private final UserRepository users;
    private final NotificationSettingsService settingsService;
    private final NotificationLog notificationLog;
    private final SpendingReports reports;
    private final BudgetService budgetService;
    private final RecurringService recurringService;
    private final ForecastService forecastService;
    private final GoalService goalService;
    private final DigestLinks links;
    private final BotTexts texts;
    private final JavaMailSender mailSender;
    private final AppProperties properties;
    private final Clock clock;

    public EmailDigests(
            UserRepository users,
            NotificationSettingsService settingsService,
            NotificationLog notificationLog,
            SpendingReports reports,
            BudgetService budgetService,
            RecurringService recurringService,
            ForecastService forecastService,
            GoalService goalService,
            DigestLinks links,
            BotTexts texts,
            JavaMailSender mailSender,
            AppProperties properties,
            Clock clock) {
        this.users = users;
        this.settingsService = settingsService;
        this.notificationLog = notificationLog;
        this.reports = reports;
        this.budgetService = budgetService;
        this.recurringService = recurringService;
        this.forecastService = forecastService;
        this.goalService = goalService;
        this.links = links;
        this.texts = texts;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
    }

    /** Every hour: the digests of the users who chose this hour; a failing one does not stop the others. */
    @Scheduled(cron = "${app.notifications.daily-cron:0 0 * * * *}", zone = "Europe/Kyiv")
    public void send() {
        int hour = ZonedDateTime.now(clock.withZone(Zones.KYIV)).getHour();
        for (User user : users.findAll()) {
            if (!user.isEnabled() || !user.isEmailVerified() || user.getDeletionScheduledAt() != null) {
                continue;
            }
            NotificationSettings settings = settingsService.of(user.getId());
            if (settings.getNotifyHour() != hour) {
                continue;
            }
            try {
                sendDue(user, settings);
            } catch (RuntimeException e) {
                log.error("Email digest of user {} failed", user.getId(), e);
            }
        }
    }

    void sendDue(User user, NotificationSettings settings) {
        LocalDate today = LocalDate.now(clock);
        Locale locale = BotTexts.locale(user.getLocale());
        if (settings.isOn(NotificationKind.EMAIL_WEEKLY)) {
            reports.weekly(user.getId(), today)
                    .ifPresent(weekly -> deliver(
                            user,
                            NotificationKind.EMAIL_WEEKLY,
                            weekly.monday().toString(),
                            weeklyMail(user.getId(), weekly, today, locale),
                            weeklyChart(weekly, locale),
                            locale));
        }
        if (settings.isOn(NotificationKind.EMAIL_MONTHLY)) {
            reports.monthly(user.getId(), today)
                    .ifPresent(monthly -> deliver(
                            user,
                            NotificationKind.EMAIL_MONTHLY,
                            monthly.month().toString(),
                            monthlyMail(user.getId(), monthly, locale),
                            monthlyChart(monthly, locale),
                            locale));
        }
    }

    // Weekly

    DigestMail weeklyMail(Long userId, SpendingReports.Weekly weekly, LocalDate today, Locale locale) {
        CompareResponse compare = weekly.compare();
        String currency = compare.currency();
        String period = BotTexts.period(weekly.from(), weekly.to(), locale);
        String previousPeriod = BotTexts.period(weekly.previousFrom(), weekly.previousTo(), locale);
        String total = BotTexts.wholeMoney(compare.totalA(), currency, locale);
        String summary;
        String subjectPart;
        if (compare.totalB().signum() == 0) {
            summary = texts.get(locale, "mail.digest.weekly.noPrevious", previousPeriod);
            subjectPart = total;
        } else if (compare.change().signum() == 0) {
            summary = texts.get(locale, "mail.digest.weekly.same", previousPeriod);
            subjectPart = texts.get(locale, "mail.digest.weekly.subjectSame");
        } else {
            int percent = SpendingReports.percent(compare.changeShare());
            boolean more = compare.change().signum() > 0;
            summary = texts.get(
                    locale,
                    more ? "mail.digest.weekly.more" : "mail.digest.weekly.less",
                    percent,
                    previousPeriod,
                    BotTexts.wholeMoney(compare.totalB(), currency, locale));
            subjectPart = texts.get(
                    locale, more ? "mail.digest.weekly.subjectMore" : "mail.digest.weekly.subjectLess", percent);
        }
        DigestMail mail = new DigestMail(
                        texts.get(locale, "mail.digest.weekly.subject", period, subjectPart),
                        texts.get(locale, "mail.digest.weekly.title", period))
                .intro("<b style=\"font-size:28px;color:#18181B\">" + BotTexts.escape(total) + "</b>")
                .intro(BotTexts.escape(summary))
                .chart(texts.get(locale, "notify.weekly.chart"));
        mail.section(texts.get(locale, "mail.digest.changes"), changes(weekly.more(), weekly.less(), currency, locale));

        List<Line> budget = new ArrayList<>();
        if (weekly.budgetUsed() != null) {
            budget.add(
                    Line.text(texts.get(locale, "mail.digest.budgetUsed", weekly.budgetUsed(), weekly.monthPassed())));
        }
        String almost = almostUsedUp(userId, YearMonth.from(today));
        if (!almost.isEmpty()) {
            budget.add(Line.text(texts.get(locale, "mail.digest.budgetAlmost", almost)));
        }
        mail.section(texts.get(locale, "mail.digest.budget"), budget);

        List<Line> payments = new ArrayList<>();
        for (OccurrenceResponse occurrence : recurringService.pending(userId, today.plusDays(PAYMENT_DAYS - 1)).stream()
                .limit(MAX_PAYMENTS)
                .toList()) {
            payments.add(Line.of(
                    BotTexts.shortDate(occurrence.date(), locale) + " · " + occurrence.name(),
                    BotTexts.amount(occurrence.type(), occurrence.amount(), occurrence.currency(), locale)));
        }
        if (payments.isEmpty()) {
            payments.add(Line.text(texts.get(locale, "mail.digest.noPayments")));
        }
        forecastService
                .firstNegativeDay(userId, today.plusDays(FORECAST_DAYS - 1))
                .map(ForecastDay::date)
                .ifPresent(day ->
                        payments.add(Line.text(texts.get(locale, "mail.digest.forecast", BotTexts.date(day, locale)))));
        mail.section(texts.get(locale, "mail.digest.payments"), payments);
        return mail.button(texts.get(locale, "mail.digest.openReports"), properties.frontendUrl("/reports"));
    }

    private byte[] weeklyChart(SpendingReports.Weekly weekly, Locale locale) {
        return SpendingCharts.cumulative(
                texts.get(locale, "notify.weekly.chart") + ", "
                        + BotTexts.symbol(weekly.compare().currency()),
                BotTexts.month(YearMonth.from(weekly.from()), locale),
                BotTexts.month(YearMonth.from(weekly.previousFrom()), locale),
                weekly.days(),
                locale);
    }

    /** The envelopes of the month with at least 80 % of their budget spent, e.g. «Кава — 82 %». */
    private String almostUsedUp(Long userId, YearMonth month) {
        return flatten(budgetService.month(userId, month).categories()).stream()
                .filter(row -> !row.archived() && row.ownAssigned().signum() > 0)
                .map(row -> new Object[] {
                    row.name(),
                    row.ownActivity()
                            .negate()
                            .multiply(BigDecimal.valueOf(100))
                            .divide(row.ownAssigned(), 0, RoundingMode.FLOOR)
                            .intValue()
                })
                .filter(pair -> (int) pair[1] >= BUDGET_WARNING)
                .map(pair -> pair[0] + " — " + pair[1] + " %")
                .collect(Collectors.joining(", "));
    }

    private static List<CategoryRow> flatten(List<CategoryRow> rows) {
        return rows.stream()
                .flatMap(row -> Stream.concat(Stream.of(row), flatten(row.children()).stream()))
                .toList();
    }

    // Monthly

    DigestMail monthlyMail(Long userId, SpendingReports.Monthly monthly, Locale locale) {
        IncomeExpenseResponse current = monthly.current();
        IncomeExpenseResponse previous = monthly.previous();
        String currency = current.currency();
        String monthName = BotTexts.monthOf(monthly.month(), locale);
        String saved = BotTexts.wholeMoney(current.net(), currency, locale);
        DigestMail mail = new DigestMail(
                        texts.get(locale, "mail.digest.monthly.subject", monthName, saved),
                        texts.get(locale, "mail.digest.monthly.title", monthName))
                .intro(BotTexts.escape(texts.get(locale, "mail.digest.monthly.compared")))
                .chart(texts.get(locale, "notify.monthly.chart"));

        List<Line> totals = new ArrayList<>();
        totals.add(total(
                texts.get(locale, "notify.monthly.income"),
                current.income(),
                previous.income(),
                currency,
                locale,
                false));
        totals.add(total(
                texts.get(locale, "notify.monthly.expense"),
                current.expense(),
                previous.expense(),
                currency,
                locale,
                true));
        totals.add(Line.of(
                texts.get(locale, "notify.monthly.saved"),
                current.savingsRate() == null
                        ? saved
                        : saved + " · "
                                + texts.get(
                                        locale,
                                        "mail.digest.savedShare",
                                        SpendingReports.percent(current.savingsRate()))));
        mail.section(BotTexts.month(monthly.month(), locale), totals);
        mail.section(
                texts.get(locale, "mail.digest.changes"), changes(monthly.more(), monthly.less(), currency, locale));

        List<Line> goals = new ArrayList<>();
        for (GoalResponse goal :
                goalService.list(userId).goals().stream().limit(MAX_GOALS).toList()) {
            String goalSaved = BotTexts.wholeMoney(goal.saved(), goal.currency(), locale);
            String target = BotTexts.wholeMoney(goal.targetAmount(), goal.currency(), locale);
            goals.add(Line.of(
                    goal.name(),
                    goal.status() == GoalStatus.ACHIEVED
                            ? texts.get(locale, "mail.digest.goalReached", goalSaved, target)
                            : texts.get(
                                    locale,
                                    "mail.digest.goalProgress",
                                    goalSaved,
                                    target,
                                    SpendingReports.percent(goal.progress()))));
        }
        mail.section(texts.get(locale, "mail.digest.goals"), goals);
        return mail.button(texts.get(locale, "mail.digest.openMonthly"), properties.frontendUrl("/reports"));
    }

    private byte[] monthlyChart(SpendingReports.Monthly monthly, Locale locale) {
        List<SpendingCharts.Bar> bars =
                monthly.bars(texts.get(locale, "bot.other"), texts.get(locale, "bot.uncategorized"));
        return bars.isEmpty()
                ? null
                : SpendingCharts.categories(
                        texts.get(locale, "notify.monthly.chart") + ", "
                                + BotTexts.symbol(monthly.current().currency()),
                        BotTexts.month(monthly.month(), locale),
                        BotTexts.month(monthly.before(), locale),
                        bars,
                        locale);
    }

    /** A total with its change; for the spending a growth is red, for the income green. */
    private static Line total(
            String label, BigDecimal current, BigDecimal previous, String currency, Locale locale, boolean spending) {
        String amount = BotTexts.wholeMoney(current, currency, locale);
        BigDecimal share = SpendingReports.change(current, previous);
        if (share == null || share.signum() == 0) {
            return Line.of(label, amount);
        }
        boolean good = spending ? share.signum() < 0 : share.signum() > 0;
        String sign = share.signum() > 0 ? "+" : "−";
        return new Line(
                label,
                amount + " · " + sign + SpendingReports.percent(share) + " %",
                good ? DigestMail.LESS : DigestMail.MORE);
    }

    private List<Line> changes(List<CategoryChange> more, List<CategoryChange> less, String currency, Locale locale) {
        List<Line> lines = new ArrayList<>();
        for (CategoryChange change : Stream.concat(more.stream(), less.stream()).toList()) {
            String name = change.name() != null ? change.name() : texts.get(locale, "bot.uncategorized");
            lines.add(new Line(
                    name,
                    DigestMail.change(change.change(), change.changeShare(), currency, locale),
                    change.change().signum() > 0 ? DigestMail.MORE : DigestMail.LESS));
        }
        return lines;
    }

    // Sending

    private void deliver(User user, NotificationKind kind, String ref, DigestMail mail, byte[] chart, Locale locale) {
        if (notificationLog.sent(user.getId(), kind, ref)) {
            return;
        }
        String footer = texts.get(locale, "mail.digest.footer");
        String unsubscribe = texts.get(locale, "mail.digest.unsubscribe");
        String settings = texts.get(locale, "mail.digest.settings");
        String page = links.page(user.getId());
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.mail().from());
            helper.setTo(user.getEmail());
            helper.setSubject(mail.subject);
            boolean withChart = chart != null && mail.hasChart();
            helper.setText(
                    mail.text(footer, unsubscribe, page),
                    mail.html(
                            footer,
                            unsubscribe,
                            page,
                            settings,
                            properties.frontendUrl("/settings?tab=notifications"),
                            withChart ? CHART : null));
            if (withChart) {
                helper.addInline(CHART, new ByteArrayResource(chart), "image/png");
            }
            // One click in the mail client unsubscribes, without opening anything (RFC 8058)
            message.setHeader("List-Unsubscribe", "<" + links.oneClick(user.getId()) + ">");
            message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            log.warn("Email digest {} of user {} was not sent: {}", kind, user.getId(), e.getMessage());
            return;
        }
        notificationLog.markSent(user.getId(), kind, ref);
    }
}
