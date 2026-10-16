package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.currency.ExchangeRateService;
import com.andrewcorp.webwallet.investment.InvestmentValuation.AccountHoldings;
import com.andrewcorp.webwallet.investment.InvestmentValuation.Holding;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.user.UserService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dividends and coupons of the brokerage accounts: received ones (the operations), expected ones (the holder on
 * the ex-dividend date should have been paid, but nothing is recorded), declared ones (the provider knows them, or the
 * coupon schedule of a bond) and a forecast (the payments of the last 12 months repeated a year later for what is
 * held now).
 */
@Service
public class DividendCalendarService {

    private static final MathContext PRECISION = MathContext.DECIMAL64;

    /** A recorded dividend counts as the payment of a declared one paid within these days. */
    private static final int MATCH_BEFORE_DAYS = 5;

    private static final int MATCH_AFTER_DAYS = 31;

    private final InvestmentOperationRepository operations;
    private final InstrumentDividendRepository dividends;
    private final InvestmentValuation valuation;
    private final AccountRepository accounts;
    private final UserService users;
    private final ExchangeRateService exchangeRates;
    private final Clock clock;

    public DividendCalendarService(
            InvestmentOperationRepository operations,
            InstrumentDividendRepository dividends,
            InvestmentValuation valuation,
            AccountRepository accounts,
            UserService users,
            ExchangeRateService exchangeRates,
            Clock clock) {
        this.operations = operations;
        this.dividends = dividends;
        this.valuation = valuation;
        this.accounts = accounts;
        this.users = users;
        this.exchangeRates = exchangeRates;
        this.clock = clock;
    }

    /**
     * @param from by default a year ago
     * @param to   by default a year ahead
     */
    @Transactional(readOnly = true)
    public CalendarResponse calendar(Long userId, Long accountId, LocalDate from, LocalDate to, String currency) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = from != null ? from : today.minusYears(1).plusDays(1);
        LocalDate end = to != null ? to : today.plusYears(1);
        if (start.isAfter(end)) {
            throw ApiException.invalid("from", "error.report.period");
        }
        if (start.plusYears(5).isBefore(end)) {
            throw ApiException.invalid("to", "error.investment.calendarTooLong");
        }
        Set<Long> scope = new HashSet<>();
        for (Account account : accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            boolean wanted = accountId != null ? account.getId().equals(accountId) : !account.isArchived();
            if (account.getType() == AccountType.INVESTMENT && wanted) {
                scope.add(account.getId());
            }
        }
        if (accountId != null && scope.isEmpty()) {
            throw ApiException.notFound();
        }
        CurrencyConverter converter = exchangeRates.converterTo(
                currency != null ? currency : users.get(userId).getBaseCurrency());

        Map<Key, List<InvestmentOperation>> positions = new LinkedHashMap<>();
        for (InvestmentOperation operation : operations.findAllOfUser(userId)) {
            if (operation.getInstrument() != null
                    && scope.contains(operation.getAccount().getId())) {
                positions
                        .computeIfAbsent(
                                new Key(operation.getAccount().getId(), operation.getInstrument()),
                                key -> new ArrayList<>())
                        .add(operation);
            }
        }
        Map<Long, List<InstrumentDividend>> declared = new HashMap<>();
        List<Long> marketIds = positions.keySet().stream()
                .map(Key::instrument)
                .filter(instrument -> instrument.getValuation() == Valuation.MARKET)
                .map(Instrument::getId)
                .distinct()
                .toList();
        if (!marketIds.isEmpty()) {
            for (InstrumentDividend dividend : dividends.findByInstrumentIdInOrderByExDate(marketIds)) {
                declared.computeIfAbsent(dividend.getInstrumentId(), id -> new ArrayList<>())
                        .add(dividend);
            }
        }

        // The totals of the 12 months around today need their payments also when the calendar shows less
        LocalDate yearAgo = today.minusYears(1).plusDays(1);
        LocalDate yearAhead = today.plusYears(1);
        LocalDate first = start.isBefore(yearAgo) ? start : yearAgo;
        LocalDate last = end.isAfter(yearAhead) ? end : yearAhead;
        List<Event> events = new ArrayList<>();
        for (Map.Entry<Key, List<InvestmentOperation>> position : positions.entrySet()) {
            Key key = position.getKey();
            Timeline timeline = new Timeline(position.getValue());
            List<InvestmentOperation> received = position.getValue().stream()
                    .filter(operation -> operation.getType() == OperationType.DIVIDEND
                            || operation.getType() == OperationType.COUPON)
                    .toList();
            BigDecimal taxRate = taxRate(received);
            Set<InvestmentOperation> matched = new HashSet<>();
            for (InvestmentOperation operation : received) {
                events.add(new Event(
                        operation.getDate(),
                        null,
                        key,
                        Status.RECEIVED,
                        timeline.heldBefore(operation.getDate().plusDays(1)),
                        null,
                        operation.getAmount(),
                        operation.getTax(),
                        operation.getCurrency(),
                        false));
            }
            Instrument instrument = key.instrument();
            BigDecimal heldNow = timeline.heldBefore(today.plusDays(1));
            if (instrument.getValuation() == Valuation.NOMINAL) {
                coupons(key, timeline, received, matched, first, last, today, events);
                continue;
            }
            List<InstrumentDividend> known = declared.getOrDefault(instrument.getId(), List.of());
            LocalDate lastKnown = null;
            for (InstrumentDividend dividend : known) {
                LocalDate payDay = dividend.payDay();
                lastKnown = lastKnown == null || payDay.isAfter(lastKnown) ? payDay : lastKnown;
                BigDecimal held =
                        dividend.getExDate().isAfter(today) ? heldNow : timeline.heldBefore(dividend.getExDate());
                if (held.signum() <= 0 || payDay.isBefore(first) || payDay.isAfter(last)) {
                    continue;
                }
                InvestmentOperation payment = received.stream()
                        .filter(operation -> !matched.contains(operation)
                                && !operation
                                        .getDate()
                                        .isBefore(dividend.getExDate().minusDays(MATCH_BEFORE_DAYS))
                                && !operation.getDate().isAfter(payDay.plusDays(MATCH_AFTER_DAYS)))
                        .findFirst()
                        .orElse(null);
                if (payment != null) {
                    matched.add(payment);
                    continue;
                }
                BigDecimal gross = dividend.getAmount().multiply(held);
                events.add(new Event(
                        payDay,
                        dividend.getExDate(),
                        key,
                        payDay.isAfter(today) ? Status.DECLARED : Status.EXPECTED,
                        held,
                        dividend.getAmount(),
                        gross,
                        gross.multiply(taxRate),
                        instrument.getCurrency(),
                        true));
            }
            if (heldNow.signum() <= 0) {
                continue;
            }
            // The last year again, after what is already known
            List<Past> past = new ArrayList<>();
            for (InstrumentDividend dividend : known) {
                if (dividend.payDay().isAfter(today.minusYears(1))
                        && !dividend.payDay().isAfter(today)) {
                    past.add(new Past(dividend.payDay(), dividend.getAmount()));
                }
            }
            if (known.isEmpty()) {
                for (InvestmentOperation operation : received) {
                    BigDecimal held = timeline.heldBefore(operation.getDate().plusDays(1));
                    if (operation.getDate().isAfter(today.minusYears(1)) && held.signum() > 0) {
                        past.add(new Past(
                                operation.getDate(), operation.getAmount().divide(held, PRECISION)));
                        lastKnown = lastKnown == null || operation.getDate().isAfter(lastKnown)
                                ? operation.getDate()
                                : lastKnown;
                    }
                }
            }
            for (Past payment : past) {
                for (int years = 1; years <= 5; years++) {
                    LocalDate day = payment.date().plusYears(years);
                    if (day.isAfter(last)) {
                        break;
                    }
                    if (!day.isAfter(today) || day.isBefore(first) || (lastKnown != null && !day.isAfter(lastKnown))) {
                        continue;
                    }
                    BigDecimal gross = payment.perShare().multiply(heldNow);
                    events.add(new Event(
                            day,
                            null,
                            key,
                            Status.FORECAST,
                            heldNow,
                            payment.perShare(),
                            gross,
                            gross.multiply(taxRate),
                            instrument.getCurrency(),
                            true));
                }
            }
        }
        events.sort(Comparator.comparing(Event::date)
                .thenComparing(event -> event.key().instrument().getSymbol()));
        return response(userId, scope, converter, events, positions, start, end, today);
    }

    /** Coupons of a bond by its schedule: those after today are known, earlier ones not recorded are expected. */
    private static void coupons(
            Key key,
            Timeline timeline,
            List<InvestmentOperation> received,
            Set<InvestmentOperation> matched,
            LocalDate start,
            LocalDate end,
            LocalDate today,
            List<Event> events) {
        Instrument bond = key.instrument();
        if (bond.getCouponRate() == null
                || bond.getCouponFrequency() == null
                || bond.getMaturityDate() == null
                || bond.getNominal() == null) {
            return;
        }
        int months = 12 / bond.getCouponFrequency();
        BigDecimal perBond = bond.getNominal()
                .multiply(bond.getCouponRate())
                .divide(BigDecimal.valueOf(100L * bond.getCouponFrequency()), 6, RoundingMode.HALF_UP);
        BigDecimal heldNow = timeline.heldBefore(today.plusDays(1));
        for (int step = 0; ; step++) {
            LocalDate day = bond.getMaturityDate().minusMonths((long) months * step);
            if (day.isBefore(start)) {
                break;
            }
            if (day.isAfter(end)) {
                continue;
            }
            BigDecimal held = day.isAfter(today) ? heldNow : timeline.heldBefore(day);
            if (held.signum() <= 0) {
                continue;
            }
            InvestmentOperation payment = received.stream()
                    .filter(operation -> !matched.contains(operation)
                            && Math.abs(ChronoUnit.DAYS.between(day, operation.getDate())) <= MATCH_AFTER_DAYS)
                    .findFirst()
                    .orElse(null);
            if (payment != null) {
                matched.add(payment);
                continue;
            }
            events.add(new Event(
                    day,
                    null,
                    key,
                    day.isAfter(today) ? Status.DECLARED : Status.EXPECTED,
                    held,
                    perBond,
                    perBond.multiply(held),
                    BigDecimal.ZERO,
                    bond.getCurrency(),
                    false));
        }
    }

    /** The part of the latest dividend withheld as tax, zero without one. */
    private static BigDecimal taxRate(List<InvestmentOperation> received) {
        return received.stream()
                .filter(operation -> operation.getAmount().signum() > 0)
                .max(Comparator.comparing(InvestmentOperation::getDate))
                .map(operation -> operation.getTax().divide(operation.getAmount(), PRECISION))
                .orElse(BigDecimal.ZERO);
    }

    private CalendarResponse response(
            Long userId,
            Set<Long> scope,
            CurrencyConverter converter,
            List<Event> events,
            Map<Key, List<InvestmentOperation>> positions,
            LocalDate start,
            LocalDate end,
            LocalDate today) {
        List<CalendarEvent> items = new ArrayList<>();
        TreeMap<YearMonth, BigDecimal[]> months = new TreeMap<>();
        for (YearMonth month = YearMonth.from(start);
                !month.isAfter(YearMonth.from(end));
                month = month.plusMonths(1)) {
            months.put(month, zeros());
        }
        BigDecimal next12 = BigDecimal.ZERO;
        BigDecimal next12Net = BigDecimal.ZERO;
        BigDecimal received12 = BigDecimal.ZERO;
        Map<Instrument, BigDecimal> receivedBy = new LinkedHashMap<>();
        LocalDate yearAhead = today.plusYears(1);
        LocalDate yearAgo = today.minusYears(1);
        for (Event event : events) {
            LocalDate rateDate = event.status() == Status.RECEIVED ? event.date() : today;
            BigDecimal gross = known(converter.convertIfKnown(event.gross(), event.currency(), rateDate));
            BigDecimal tax = known(converter.convertIfKnown(event.tax(), event.currency(), rateDate));
            if (event.status() == Status.RECEIVED
                    && event.date().isAfter(yearAgo)
                    && !event.date().isAfter(today)) {
                received12 = received12.add(gross.subtract(tax));
                receivedBy.merge(event.key().instrument(), gross.subtract(tax), BigDecimal::add);
            }
            if (event.status() != Status.RECEIVED
                    && event.date().isAfter(today)
                    && !event.date().isAfter(yearAhead)) {
                next12 = next12.add(gross);
                next12Net = next12Net.add(gross.subtract(tax));
            }
            if (event.date().isBefore(start) || event.date().isAfter(end)) {
                continue;
            }
            months.get(YearMonth.from(event.date()))[event.status().ordinal()] =
                    months.get(YearMonth.from(event.date()))[event.status().ordinal()].add(gross);
            Instrument instrument = event.key().instrument();
            items.add(new CalendarEvent(
                    event.date(),
                    event.exDate(),
                    event.status(),
                    event.key().accountId(),
                    instrument.getId(),
                    instrument.getSymbol(),
                    instrument.getName(),
                    instrument.getType(),
                    money(event.quantity()),
                    event.perShare(),
                    money(event.gross()),
                    money(event.tax()),
                    money(event.gross().subtract(event.tax())),
                    event.currency(),
                    money(gross),
                    money(gross.subtract(tax)),
                    event.taxEstimated()));
        }

        BigDecimal value = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (AccountHoldings holdings :
                valuation.snapshot(userId, today).accounts().values()) {
            if (!scope.contains(holdings.accountId())) {
                continue;
            }
            for (Holding holding : holdings.holdings()) {
                String currency = holding.instrument().getCurrency();
                value = value.add(known(converter.convertIfKnown(holding.value(), currency, today)));
                cost = cost.add(known(converter.convertIfKnown(holding.lots().cost(), currency, today)));
            }
        }
        return new CalendarResponse(
                converter.target(),
                start,
                end,
                money(next12),
                money(next12Net),
                money(next12.divide(BigDecimal.valueOf(12), PRECISION)),
                share(next12, value),
                share(next12, cost),
                money(received12),
                months.entrySet().stream()
                        .map(entry -> new MonthPayments(
                                entry.getKey(),
                                money(entry.getValue()[Status.RECEIVED.ordinal()]),
                                money(entry.getValue()[Status.EXPECTED.ordinal()]),
                                money(entry.getValue()[Status.DECLARED.ordinal()]),
                                money(entry.getValue()[Status.FORECAST.ordinal()])))
                        .toList(),
                receivedBy.entrySet().stream()
                        .map(entry -> new InstrumentIncome(
                                entry.getKey().getId(), entry.getKey().getSymbol(), money(entry.getValue())))
                        .sorted(Comparator.comparing(InstrumentIncome::amount).reversed())
                        .toList(),
                items,
                converter.unconverted());
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] values = new BigDecimal[Status.values().length];
        Arrays.fill(values, BigDecimal.ZERO);
        return values;
    }

    private static BigDecimal known(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal share(BigDecimal part, BigDecimal total) {
        return total.signum() <= 0 ? null : part.divide(total, 4, RoundingMode.HALF_UP);
    }

    /** What was held of the instrument on the account before a day: the trades of the earlier days. */
    private static final class Timeline {

        private final List<InvestmentOperation> operations;

        Timeline(List<InvestmentOperation> operations) {
            this.operations = operations.stream()
                    .sorted(Comparator.comparing(InvestmentOperation::getDate))
                    .toList();
        }

        BigDecimal heldBefore(LocalDate day) {
            BigDecimal held = BigDecimal.ZERO;
            for (InvestmentOperation operation : operations) {
                if (!operation.getDate().isBefore(day)) {
                    break;
                }
                held = switch (operation.getType()) {
                    case BUY -> held.add(operation.getQuantity());
                    case SELL -> held.subtract(operation.getQuantity());
                    case SPLIT ->
                        held.multiply(BigDecimal.valueOf(operation.getSplitTo()))
                                .divide(BigDecimal.valueOf(operation.getSplitFrom()), PRECISION);
                    default -> held;
                };
            }
            return held;
        }
    }

    private record Key(Long accountId, Instrument instrument) {}

    private record Past(LocalDate date, BigDecimal perShare) {}

    private record Event(
            LocalDate date,
            LocalDate exDate,
            Key key,
            Status status,
            BigDecimal quantity,
            BigDecimal perShare,
            BigDecimal gross,
            BigDecimal tax,
            String currency,
            boolean taxEstimated) {}

    /** Order matters: it indexes the monthly totals. */
    public enum Status {
        /** Recorded as an operation. */
        RECEIVED,
        /** Held on the ex-dividend date and due, but not recorded. */
        EXPECTED,
        /** Declared by the company, or the coupon schedule of a bond. */
        DECLARED,
        /** The payments of the last 12 months repeated for what is held now. */
        FORECAST
    }

    /**
     * A payment, in the currency of the instrument and ({@code InTarget}) in the currency of the calendar at the rate
     * of its date (received) or of today.
     *
     * @param date        the payment date (the ex-dividend date when the payment date is unknown)
     * @param exDate      known for declared and expected dividends
     * @param quantity    held on the ex-dividend date, or now for future ones
     * @param perShare    null for received ones
     * @param tax         withheld (received) or estimated from the last dividend of the instrument
     * @param taxEstimated whether the tax is an estimate
     */
    public record CalendarEvent(
            LocalDate date,
            @Schema(nullable = true) LocalDate exDate,
            Status status,
            Long accountId,
            Long instrumentId,
            String symbol,
            String name,
            InstrumentType type,
            BigDecimal quantity,
            @Schema(nullable = true) BigDecimal perShare,
            BigDecimal gross,
            BigDecimal tax,
            BigDecimal net,
            String currency,
            BigDecimal grossInTarget,
            BigDecimal netInTarget,
            boolean taxEstimated) {}

    /** Gross payments of the month by status, in the currency of the calendar. */
    public record MonthPayments(
            YearMonth month, BigDecimal received, BigDecimal expected, BigDecimal declared, BigDecimal forecast) {}

    public record InstrumentIncome(Long instrumentId, String symbol, BigDecimal amount) {}

    /**
     * @param next12Months       gross payments expected over the next 12 months (declared and forecast)
     * @param next12MonthsNet    the same after the (estimated) tax
     * @param yield              next12Months / the value of the positions
     * @param yieldOnCost        next12Months / the cost of the positions
     * @param received12Months   received after tax over the last 12 months
     * @param receivedByInstrument received after tax over the last 12 months per instrument, largest first
     */
    public record CalendarResponse(
            String currency,
            LocalDate from,
            LocalDate to,
            BigDecimal next12Months,
            BigDecimal next12MonthsNet,
            BigDecimal monthly,
            @Schema(nullable = true) BigDecimal yield,
            @Schema(nullable = true) BigDecimal yieldOnCost,
            BigDecimal received12Months,
            List<MonthPayments> months,
            List<InstrumentIncome> receivedByInstrument,
            List<CalendarEvent> events,
            List<String> unconvertedCurrencies) {}
}
