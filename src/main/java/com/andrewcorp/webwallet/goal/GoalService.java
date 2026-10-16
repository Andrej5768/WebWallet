package com.andrewcorp.webwallet.goal;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.util.Money;
import com.andrewcorp.webwallet.currency.ConverterCache;
import com.andrewcorp.webwallet.currency.Converters;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalRequest;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalResponse;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalStatus;
import com.andrewcorp.webwallet.goal.GoalDtos.GoalsResponse;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.user.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Savings goals. The pace is linear: from the progress when the goal was set to the target on the deadline; a goal
 * more than {@value #BEHIND_PERCENT}% of the target below it is behind.
 */
@Service
public class GoalService {

    static final int BEHIND_PERCENT = 3;

    /** What the database holds: 15 digits before the decimal point. */
    private static final BigDecimal MAX_SAVED = BigDecimal.TEN.pow(15);

    private final GoalRepository goals;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final UserService users;
    private final Converters converters;
    private final Clock clock;

    public GoalService(
            GoalRepository goals,
            AccountRepository accounts,
            AccountService accountService,
            UserService users,
            Converters converters,
            Clock clock) {
        this.goals = goals;
        this.accounts = accounts;
        this.accountService = accountService;
        this.users = users;
        this.converters = converters;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GoalsResponse list(Long userId) {
        Progress progress = new Progress(userId);
        CurrencyConverter base = progress.converter(users.get(userId).getBaseCurrency());
        LocalDate today = LocalDate.now(clock);
        List<GoalResponse> responses = new ArrayList<>();
        BigDecimal saved = BigDecimal.ZERO;
        BigDecimal target = BigDecimal.ZERO;
        BigDecimal monthly = BigDecimal.ZERO;
        for (Goal goal : goals.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            GoalResponse response = response(goal, progress.saved(goal), today);
            responses.add(response);
            saved = saved.add(base.convert(response.saved(), goal.getCurrency(), today));
            target = target.add(base.convert(response.targetAmount(), goal.getCurrency(), today));
            if (response.monthlyNeeded() != null && response.status() != GoalStatus.ACHIEVED) {
                monthly = monthly.add(base.convert(response.monthlyNeeded(), goal.getCurrency(), today));
            }
        }
        return new GoalsResponse(base.target(), saved, target, monthly, responses, progress.unconverted());
    }

    @Transactional(readOnly = true)
    public GoalResponse get(Long userId, Long id) {
        Goal goal = find(userId, id);
        return response(goal, new Progress(userId).saved(goal), LocalDate.now(clock));
    }

    @Transactional
    public GoalResponse create(Long userId, GoalRequest request) {
        Goal goal = new Goal(userId);
        apply(userId, goal, request);
        goal.setStartDate(LocalDate.now(clock));
        goal.setStartAmount(Money.orZero(new Progress(userId).saved(goal)));
        return response(goals.save(goal), goal.getStartAmount(), LocalDate.now(clock));
    }

    /** A goal moved to another account starts its pace again from the balance of that account. */
    @Transactional
    public GoalResponse update(Long userId, Long id, GoalRequest request) {
        Goal goal = find(userId, id);
        Long accountBefore = goal.getAccount() != null ? goal.getAccount().getId() : null;
        apply(userId, goal, request);
        BigDecimal saved = new Progress(userId).saved(goal);
        if (!Objects.equals(accountBefore, request.accountId())) {
            goal.setStartDate(LocalDate.now(clock));
            goal.setStartAmount(Money.orZero(saved));
        }
        return response(goal, saved, LocalDate.now(clock));
    }

    /** Adds to the progress of a goal kept by hand. */
    @Transactional
    public GoalResponse contribute(Long userId, Long id, BigDecimal amount) {
        Goal goal = find(userId, id);
        if (goal.getAccount() != null) {
            throw ApiException.invalid("amount", "error.goal.account");
        }
        BigDecimal saved = goal.getSaved().add(amount);
        if (saved.signum() < 0) {
            throw ApiException.invalid("amount", "error.goal.negative");
        }
        if (saved.compareTo(MAX_SAVED) >= 0) {
            throw ApiException.invalid("amount", "error.goal.tooLarge");
        }
        goal.setSaved(saved);
        return response(goal, saved, LocalDate.now(clock));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        goals.delete(find(userId, id));
    }

    private Goal find(Long userId, Long id) {
        return goals.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    private void apply(Long userId, Goal goal, GoalRequest request) {
        Account account = null;
        if (request.accountId() != null) {
            account = accounts.findByIdAndUserId(request.accountId(), userId)
                    .orElseThrow(() -> ApiException.invalid("accountId", "error.notFound"));
        }
        String currency = request.currency();
        if (currency == null) {
            currency =
                    account != null ? account.getCurrency() : users.get(userId).getBaseCurrency();
        }
        goal.setName(request.name().trim());
        goal.setTargetAmount(request.targetAmount());
        goal.setCurrency(currency);
        goal.setTargetDate(request.targetDate());
        goal.setAccount(account);
        goal.setSaved(account == null && request.saved() != null ? request.saved() : BigDecimal.ZERO);
        goal.setIcon(request.icon());
        goal.setColor(request.color());
        if (request.achieved() != null) {
            goal.setAchievedOn(request.achieved() ? achievedOn(goal) : null);
        }
        if (request.sortOrder() != null) {
            goal.setSortOrder(request.sortOrder());
        }
    }

    private LocalDate achievedOn(Goal goal) {
        return goal.getAchievedOn() != null ? goal.getAchievedOn() : LocalDate.now(clock);
    }

    /**
     * @param known the progress, null when it is not known (an account in a currency without a rate): then it counts
     *              as zero and the pace is not judged
     */
    private static GoalResponse response(Goal goal, BigDecimal known, LocalDate today) {
        BigDecimal saved = Money.orZero(known);
        BigDecimal target = goal.getTargetAmount();
        BigDecimal remaining = target.subtract(saved).max(BigDecimal.ZERO);
        BigDecimal progress = saved.divide(target, 4, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE)
                .max(BigDecimal.ZERO);
        LocalDate deadline = goal.getTargetDate();
        BigDecimal expected = null;
        Integer monthsLeft = null;
        BigDecimal monthlyNeeded = null;
        GoalStatus status;
        if (goal.getAchievedOn() != null || remaining.signum() == 0) {
            status = GoalStatus.ACHIEVED;
        } else if (deadline == null) {
            status = GoalStatus.NO_DATE;
        } else if (deadline.isBefore(today)) {
            status = GoalStatus.OVERDUE;
        } else if (known == null) {
            status = GoalStatus.ON_TRACK;
        } else {
            expected = expected(goal, today);
            BigDecimal tolerance = target.multiply(BigDecimal.valueOf(BEHIND_PERCENT))
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            status = saved.compareTo(expected.subtract(tolerance)) < 0 ? GoalStatus.BEHIND : GoalStatus.ON_TRACK;
        }
        if (deadline != null && status != GoalStatus.ACHIEVED) {
            monthsLeft = monthsLeft(today, deadline);
            monthlyNeeded = monthsLeft == 0
                    ? remaining
                    : remaining.divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.HALF_UP);
        }
        return new GoalResponse(
                goal.getId(),
                goal.getName(),
                target,
                goal.getCurrency(),
                deadline,
                goal.getAccount() != null ? goal.getAccount().getId() : null,
                saved,
                remaining,
                progress,
                goal.getStartDate(),
                goal.getStartAmount(),
                expected,
                monthsLeft,
                monthlyNeeded,
                status,
                goal.getAchievedOn(),
                goal.getIcon(),
                goal.getColor(),
                goal.getSortOrder());
    }

    /** The start amount plus the part of the rest that the time elapsed so far stands for. */
    private static BigDecimal expected(Goal goal, LocalDate today) {
        long total = ChronoUnit.DAYS.between(goal.getStartDate(), goal.getTargetDate());
        long elapsed = ChronoUnit.DAYS.between(goal.getStartDate(), today);
        BigDecimal start = goal.getStartAmount().min(goal.getTargetAmount());
        if (total <= 0) {
            return goal.getTargetAmount();
        }
        return start.add(goal.getTargetAmount()
                .subtract(start)
                .multiply(BigDecimal.valueOf(Math.max(0, elapsed)))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
    }

    /** Months to the deadline, a started month counted as a whole one; 0 once it has passed. */
    static int monthsLeft(LocalDate today, LocalDate deadline) {
        if (deadline.isBefore(today)) {
            return 0;
        }
        long months = ChronoUnit.MONTHS.between(today, deadline);
        return (int) Math.max(1, today.plusMonths(months).isBefore(deadline) ? months + 1 : months);
    }

    /** Progress of the goals of a user: balances and converters are loaded once. */
    private final class Progress {

        private final Long userId;
        private final ConverterCache cache = converters.cache();
        private final LocalDate today = LocalDate.now(clock);
        private Map<Long, BigDecimal> balances;

        Progress(Long userId) {
            this.userId = userId;
        }

        BigDecimal saved(Goal goal) {
            Account account = goal.getAccount();
            if (account == null) {
                return goal.getSaved();
            }
            if (balances == null) {
                balances = accountService.balances(userId);
            }
            BigDecimal balance = balances.getOrDefault(account.getId(), account.getOpeningBalance());
            BigDecimal converted = converter(goal.getCurrency()).convertIfKnown(balance, account.getCurrency(), today);
            return converted != null ? Money.round2(converted).max(BigDecimal.ZERO) : null;
        }

        CurrencyConverter converter(String currency) {
            return cache.to(currency);
        }

        List<String> unconverted() {
            return cache.unconverted();
        }
    }
}
