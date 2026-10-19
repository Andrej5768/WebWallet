package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.util.Money;
import com.andrewcorp.webwallet.common.web.Periods;
import com.andrewcorp.webwallet.currency.Converters;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.investment.Deposits.Deposit;
import com.andrewcorp.webwallet.investment.HistoryRepository.Movement;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The value of the brokerage accounts day by day: their cash (the transfers of the ledger and the cash of the
 * operations) and positions at the closes of the day, in a chosen currency at the NBU rates of the day; the net
 * deposits; and, for comparison, what the same deposits would be worth in an index fund bought and sold at the closes
 * of their days.
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    /** Longer periods are sampled weekly. */
    static final int MAX_DAILY_POINTS = 400;

    /** The longest period that can be asked for. */
    static final int MAX_YEARS = 20;

    private static final MathContext PRECISION = MathContext.DECIMAL64;

    private final Converters converters;
    private final InvestmentAccounts investmentAccounts;
    private final InvestmentOperationRepository operations;
    private final Deposits deposits;
    private final InstrumentService instrumentService;
    private final InstrumentPriceRepository prices;
    private final Clock clock;

    public HistoryService(
            Converters converters,
            InvestmentAccounts investmentAccounts,
            InvestmentOperationRepository operations,
            Deposits deposits,
            InstrumentService instrumentService,
            InstrumentPriceRepository prices,
            Clock clock) {
        this.converters = converters;
        this.investmentAccounts = investmentAccounts;
        this.operations = operations;
        this.deposits = deposits;
        this.instrumentService = instrumentService;
        this.prices = prices;
        this.clock = clock;
    }

    /**
     * @param accountId one brokerage account, or all that are not archived
     * @param from      by default the first day with money on the accounts; an earlier day starts there too
     * @param currency  by default the base currency
     * @param benchmark none means no comparison
     */
    @Transactional
    public HistoryResponse history(
            Long userId, Long accountId, LocalDate from, LocalDate to, String currency, Benchmark benchmark) {
        LocalDate today = LocalDate.now(clock);
        if (to != null) {
            Periods.checkDate(to, "to");
        }
        LocalDate end = to != null && to.isBefore(today) ? to : today;
        if (from != null) {
            Periods.checkYears(from, end, MAX_YEARS, "from");
        }
        Map<Long, Account> scope = investmentAccounts.scope(userId, accountId);
        CurrencyConverter converter = converters.to(userId, currency);

        // Everything that changes the money of the accounts, by day
        State state = new State();
        TreeMap<LocalDate, List<Runnable>> events = new TreeMap<>();
        List<Movement> movements = deposits.movements(scope);
        for (Movement movement : movements) {
            events.computeIfAbsent(movement.getDate(), day -> new ArrayList<>()).add(() -> state.move(movement, scope));
        }
        List<InvestmentOperation> all = operations.findAllOfUser(userId).stream()
                .filter(operation -> scope.containsKey(operation.getAccount().getId()))
                .toList();
        Map<Instrument, List<InvestmentOperation>> byInstrument = new LinkedHashMap<>();
        for (InvestmentOperation operation : all) {
            events.computeIfAbsent(operation.getDate(), day -> new ArrayList<>())
                    .add(() -> state.apply(operation));
            if (operation.getInstrument() != null) {
                byInstrument
                        .computeIfAbsent(operation.getInstrument(), instrument -> new ArrayList<>())
                        .add(operation);
            }
        }
        Map<Instrument, PositionTimeline> held = new LinkedHashMap<>();
        byInstrument.forEach((instrument, list) -> held.put(instrument, new PositionTimeline(list)));
        // Nothing happened before the first event: the history starts there at the earliest
        LocalDate first = events.isEmpty() ? end : events.firstKey();
        LocalDate start = from != null && from.isAfter(first) ? from : first;
        if (start.isAfter(end)) {
            throw ApiException.invalid("from", "error.period.order");
        }
        for (Account account : scope.values()) {
            state.cash.merge(account.getCurrency(), account.getOpeningBalance(), BigDecimal::add);
        }
        List<Deposit> deposited = Deposits.of(scope, movements, all, first);

        Map<Long, NavigableMap<LocalDate, BigDecimal>> closes = operationCloses(all, first, end);
        Instrument fund = benchmark != null ? benchmarkFund(benchmark) : null;
        NavigableMap<LocalDate, BigDecimal> fundCloses = fund != null
                ? closes(List.of(fund.getId()), first, end).getOrDefault(fund.getId(), new TreeMap<>())
                : null;

        CurrencyConverter toFund = fund != null ? converters.of(fund.getCurrency()) : null;
        List<HistoryPoint> points = new ArrayList<>();
        long days = ChronoUnit.DAYS.between(start, end);
        int step = days > MAX_DAILY_POINTS ? 7 : 1;
        BigDecimal fundUnits = BigDecimal.ZERO;
        // Deposits made before the first close of the fund wait for it
        BigDecimal fundPending = BigDecimal.ZERO;
        int next = 0;
        BigDecimal depositedTotal = BigDecimal.ZERO;
        // Over the period shown: every day counts, also between the sampled ones
        ReturnTracker returns = new ReturnTracker();
        ReturnTracker fundReturns = new ReturnTracker();
        for (LocalDate day = first; !day.isAfter(end); day = day.plusDays(1)) {
            BigDecimal depositedBefore = depositedTotal;
            for (Runnable event : events.getOrDefault(day, List.of())) {
                event.run();
            }
            for (; next < deposited.size() && !deposited.get(next).date().isAfter(day); next++) {
                Deposit deposit = deposited.get(next);
                depositedTotal = depositedTotal.add(converter.convert(deposit.amount(), deposit.currency(), day));
                if (fund != null) {
                    fundPending = fundPending.add(toFund.convert(deposit.amount(), deposit.currency(), day));
                }
            }
            Map.Entry<LocalDate, BigDecimal> fundClose = fund != null ? fundCloses.floorEntry(day) : null;
            if (fundClose != null && fundPending.signum() != 0) {
                fundUnits = fundUnits.add(fundPending.divide(fundClose.getValue(), PRECISION));
                fundPending = BigDecimal.ZERO;
            }
            if (day.isBefore(start)) {
                continue;
            }
            BigDecimal value = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> cash : state.cash.entrySet()) {
                value = value.add(converter.convert(cash.getValue(), cash.getKey(), day));
            }
            for (Map.Entry<Instrument, PositionTimeline> position : held.entrySet()) {
                BigDecimal quantity = position.getValue().heldAfter(day);
                if (quantity.signum() == 0) {
                    continue;
                }
                Instrument instrument = position.getKey();
                BigDecimal price = price(instrument, closes, state.lastPrices, day);
                if (price != null) {
                    value = value.add(converter.convert(quantity.multiply(price), instrument.getCurrency(), day));
                }
            }
            BigDecimal fundValue = fundClose != null
                    ? converter.convert(fundUnits.multiply(fundClose.getValue()), fund.getCurrency(), day)
                    : null;
            BigDecimal depositedToday = depositedTotal.subtract(depositedBefore);
            returns.add(day, value, depositedToday);
            if (fundValue != null) {
                fundReturns.add(day, fundValue, depositedToday);
            }
            if (ChronoUnit.DAYS.between(day, end) % step != 0) {
                continue;
            }
            points.add(new HistoryPoint(
                    day,
                    Money.round2(value),
                    Money.round2(depositedTotal),
                    fundValue != null ? Money.round2(fundValue) : null));
        }
        return new HistoryResponse(
                converter.target(),
                start,
                end,
                fund != null
                        ? new BenchmarkInfo(
                                benchmark,
                                fund.getSymbol(),
                                fund.getName(),
                                fundCloses.isEmpty() ? null : fundCloses.firstKey())
                        : null,
                points,
                new Returns(
                        returns.twr(),
                        returns.twrAnnualized(),
                        returns.xirr(),
                        fund != null ? fundReturns.twr() : null,
                        fund != null ? fundReturns.xirr() : null),
                converter.unconverted());
    }

    /** The fund of the benchmark, added (and priced by the task) when first asked for; null when unavailable. */
    private Instrument benchmarkFund(Benchmark benchmark) {
        try {
            return instrumentService.benchmark(benchmark);
        } catch (ApiException e) {
            log.warn("Benchmark {} unavailable: {}", benchmark, e.code());
            return null;
        }
    }

    private Map<Long, NavigableMap<LocalDate, BigDecimal>> operationCloses(
            List<InvestmentOperation> all, LocalDate from, LocalDate to) {
        List<Long> ids = all.stream()
                .map(InvestmentOperation::getInstrument)
                .filter(instrument -> instrument != null && instrument.getValuation() == Valuation.MARKET)
                .map(Instrument::getId)
                .distinct()
                .toList();
        return closes(ids, from, to);
    }

    /** Closes from a few days before the period, so that its first days have the close of the day before. */
    private Map<Long, NavigableMap<LocalDate, BigDecimal>> closes(List<Long> ids, LocalDate from, LocalDate to) {
        Map<Long, NavigableMap<LocalDate, BigDecimal>> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (InstrumentPrice price : prices.findByInstrumentIdInAndDateBetween(ids, from.minusDays(10), to)) {
            result.computeIfAbsent(price.getInstrumentId(), id -> new TreeMap<>())
                    .put(price.getDate(), price.getClose());
        }
        return result;
    }

    /** The close of the day, else the price of the last trade (before the first known close). */
    private static BigDecimal price(
            Instrument instrument,
            Map<Long, NavigableMap<LocalDate, BigDecimal>> closes,
            Map<Instrument, BigDecimal> lastPrices,
            LocalDate day) {
        switch (instrument.getValuation()) {
            case MARKET -> {
                NavigableMap<LocalDate, BigDecimal> known = closes.get(instrument.getId());
                Map.Entry<LocalDate, BigDecimal> close = known != null ? known.floorEntry(day) : null;
                if (close != null) {
                    return close.getValue();
                }
            }
            case NOMINAL -> {
                BigDecimal price = BondValuation.price(instrument, day);
                if (price != null) {
                    return price;
                }
            }
            case MANUAL -> {
                if (instrument.getManualPrice() != null
                        && (instrument.getManualPriceDate() == null
                                || !day.isBefore(instrument.getManualPriceDate()))) {
                    return instrument.getManualPrice();
                }
            }
        }
        return lastPrices.get(instrument);
    }

    /** The cash of the accounts and the last trade prices while the days are replayed. */
    private static final class State {

        private final Map<String, BigDecimal> cash = new TreeMap<>();
        private final Map<Instrument, BigDecimal> lastPrices = new HashMap<>();

        void move(Movement movement, Map<Long, Account> scope) {
            Account source = scope.get(movement.getAccountId());
            Account target = movement.getToAccountId() != null ? scope.get(movement.getToAccountId()) : null;
            if (source != null) {
                BigDecimal change = movement.getType() == TransactionType.INCOME
                        ? movement.getAmount()
                        : movement.getAmount().negate();
                cash.merge(source.getCurrency(), change, BigDecimal::add);
            }
            if (target != null) {
                cash.merge(target.getCurrency(), movement.getToAmount(), BigDecimal::add);
            }
        }

        void apply(InvestmentOperation operation) {
            operation.cashMoves().forEach((currency, amount) -> cash.merge(currency, amount, BigDecimal::add));
            Instrument instrument = operation.getInstrument();
            switch (operation.getType()) {
                case BUY, SELL -> lastPrices.put(instrument, operation.getPrice());
                case SPLIT -> {
                    BigDecimal factor = PositionTimeline.splitFactor(operation);
                    lastPrices.computeIfPresent(instrument, (key, price) -> price.divide(factor, PRECISION));
                }
                default -> {}
            }
        }
    }

    /**
     * @param benchmark  what the same deposits would be worth in the fund; null before its first known close
     */
    public record HistoryPoint(
            LocalDate date,
            BigDecimal value,
            BigDecimal deposited,
            @Schema(nullable = true) BigDecimal benchmark) {}

    /** @param pricesFrom the first known close of the fund; null while its prices are still loading */
    public record BenchmarkInfo(
            Benchmark key,
            String symbol,
            String name,
            @Schema(nullable = true) LocalDate pricesFrom) {}

    /**
     * The returns over the period, in its currency, as shares (0.12 is 12 %); null when they cannot be told.
     *
     * @param twr            time-weighted: the daily returns chained, without the effect of the deposits
     * @param twrAnnualized  the time-weighted return per year, for periods of a year or longer
     * @param xirr           money-weighted, per year: the rate at which the value at the start and the deposits grow
     *                       to the value at the end; for periods of {@value ReturnTracker#MIN_DAYS_FOR_XIRR} days or
     *                       longer
     * @param benchmarkTwr   of the same deposits in the fund, from its first close in the period
     * @param benchmarkXirr  of the same deposits in the fund
     */
    public record Returns(
            @Schema(nullable = true) BigDecimal twr,
            @Schema(nullable = true) BigDecimal twrAnnualized,
            @Schema(nullable = true) BigDecimal xirr,
            @Schema(nullable = true) BigDecimal benchmarkTwr,
            @Schema(nullable = true) BigDecimal benchmarkXirr) {}

    /**
     * @param benchmark null without a comparison or when the market data provider is not configured
     * @param points    daily, weekly (counted back from the end) for periods over {@value #MAX_DAILY_POINTS} days
     * @param returns   computed from every day of the period, also when the points are weekly
     */
    public record HistoryResponse(
            String currency,
            LocalDate from,
            LocalDate to,
            @Schema(nullable = true) BenchmarkInfo benchmark,
            List<HistoryPoint> points,
            Returns returns,
            List<String> unconvertedCurrencies) {}
}
