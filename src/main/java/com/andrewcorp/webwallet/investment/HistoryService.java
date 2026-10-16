package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.web.ExternalCalls;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.currency.ExchangeRateService;
import com.andrewcorp.webwallet.investment.HistoryRepository.Movement;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider.Listing;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.user.UserService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
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

    /** Longer periods are sampled weekly. */
    static final int MAX_DAILY_POINTS = 400;

    private static final MathContext PRECISION = MathContext.DECIMAL64;

    private final AccountRepository accounts;
    private final InvestmentOperationRepository operations;
    private final HistoryRepository history;
    private final InstrumentRepository instruments;
    private final InstrumentPriceRepository prices;
    private final MarketDataProvider provider;
    private final UserService users;
    private final ExchangeRateService exchangeRates;
    private final Clock clock;

    public HistoryService(
            AccountRepository accounts,
            InvestmentOperationRepository operations,
            HistoryRepository history,
            InstrumentRepository instruments,
            InstrumentPriceRepository prices,
            MarketDataProvider provider,
            UserService users,
            ExchangeRateService exchangeRates,
            Clock clock) {
        this.accounts = accounts;
        this.operations = operations;
        this.history = history;
        this.instruments = instruments;
        this.prices = prices;
        this.provider = provider;
        this.users = users;
        this.exchangeRates = exchangeRates;
        this.clock = clock;
    }

    /**
     * @param accountId one brokerage account, or all that are not archived
     * @param from      by default the first day with money on the accounts
     * @param currency  by default the base currency
     * @param benchmark none means no comparison
     */
    @Transactional
    public HistoryResponse history(
            Long userId, Long accountId, LocalDate from, LocalDate to, String currency, Benchmark benchmark) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = to != null && to.isBefore(today) ? to : today;
        Map<Long, Account> scope = new HashMap<>();
        for (Account account : accounts.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            boolean wanted = accountId != null ? account.getId().equals(accountId) : !account.isArchived();
            if (account.getType() == AccountType.INVESTMENT && wanted) {
                scope.put(account.getId(), account);
            }
        }
        if (accountId != null && scope.isEmpty()) {
            throw ApiException.notFound();
        }
        CurrencyConverter converter = exchangeRates.converterTo(
                currency != null ? currency : users.get(userId).getBaseCurrency());

        // Everything that changes the money of the accounts, by day
        TreeMap<LocalDate, List<Runnable>> events = new TreeMap<>();
        State state = new State();
        List<Movement> movements = scope.isEmpty() ? List.of() : history.findMovements(scope.keySet());
        for (Movement movement : movements) {
            events.computeIfAbsent(movement.getDate(), day -> new ArrayList<>()).add(() -> state.move(movement, scope));
        }
        List<InvestmentOperation> all = operations.findAllOfUser(userId).stream()
                .filter(operation -> scope.containsKey(operation.getAccount().getId()))
                .toList();
        for (InvestmentOperation operation : all) {
            events.computeIfAbsent(operation.getDate(), day -> new ArrayList<>())
                    .add(() -> state.apply(operation));
        }
        LocalDate first = events.isEmpty() ? end : events.firstKey();
        LocalDate start = from != null ? from : first;
        if (start.isAfter(end)) {
            throw ApiException.invalid("from", "error.report.period");
        }
        // Opening balances are money the accounts started with
        for (Account account : scope.values()) {
            state.cash.merge(account.getCurrency(), account.getOpeningBalance(), BigDecimal::add);
            state.deposits.add(new Deposit(first, account.getCurrency(), account.getOpeningBalance()));
        }

        Map<Long, NavigableMap<LocalDate, BigDecimal>> closes = operationCloses(all, first, end);
        Instrument fund = benchmark != null ? benchmarkInstrument(benchmark) : null;
        NavigableMap<LocalDate, BigDecimal> fundCloses = fund != null
                ? closes(List.of(fund.getId()), first, end).getOrDefault(fund.getId(), new TreeMap<>())
                : null;

        CurrencyConverter toFund = fund != null ? exchangeRates.converterTo(fund.getCurrency()) : null;
        List<HistoryPoint> points = new ArrayList<>();
        long days = ChronoUnit.DAYS.between(start, end);
        int step = days > MAX_DAILY_POINTS ? 7 : 1;
        BigDecimal fundUnits = BigDecimal.ZERO;
        int deposited = 0;
        BigDecimal depositedTotal = BigDecimal.ZERO;
        for (LocalDate day = first.isBefore(start) ? first : start; !day.isAfter(end); day = day.plusDays(1)) {
            for (Runnable event : events.getOrDefault(day, List.of())) {
                event.run();
            }
            // Deposits of the day: counted, and bought in the fund at its close
            for (;
                    deposited < state.deposits.size()
                            && !state.deposits.get(deposited).date().isAfter(day);
                    deposited++) {
                Deposit deposit = state.deposits.get(deposited);
                depositedTotal =
                        depositedTotal.add(known(converter.convertIfKnown(deposit.amount(), deposit.currency(), day)));
                if (fund != null && fundCloses.floorEntry(day) != null) {
                    BigDecimal inFund = known(toFund.convertIfKnown(deposit.amount(), deposit.currency(), day));
                    fundUnits = fundUnits.add(
                            inFund.divide(fundCloses.floorEntry(day).getValue(), PRECISION));
                }
            }
            boolean sampled = !day.isBefore(start) && (ChronoUnit.DAYS.between(day, end) % step == 0);
            if (!sampled) {
                continue;
            }
            BigDecimal value = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> cash : state.cash.entrySet()) {
                value = value.add(known(converter.convertIfKnown(cash.getValue(), cash.getKey(), day)));
            }
            for (Map.Entry<Instrument, BigDecimal> held : state.quantities.entrySet()) {
                if (held.getValue().signum() == 0) {
                    continue;
                }
                BigDecimal price = price(held.getKey(), closes, state.lastPrices, day);
                if (price != null) {
                    value = value.add(known(converter.convertIfKnown(
                            held.getValue().multiply(price), held.getKey().getCurrency(), day)));
                }
            }
            BigDecimal fundValue = null;
            if (fund != null && fundCloses.floorEntry(day) != null) {
                fundValue = known(converter.convertIfKnown(
                        fundUnits.multiply(fundCloses.floorEntry(day).getValue()), fund.getCurrency(), day));
            }
            points.add(new HistoryPoint(
                    day, money(value), money(depositedTotal), fundValue != null ? money(fundValue) : null));
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
                converter.unconverted());
    }

    /** The fund of the benchmark, added (and priced by the task) when first asked for. */
    private Instrument benchmarkInstrument(Benchmark benchmark) {
        Instrument fund = instruments.findMarketBySymbol(benchmark.symbol()).stream()
                .findFirst()
                .orElse(null);
        if (fund == null) {
            if (!provider.enabled()) {
                return null;
            }
            Listing listing = ExternalCalls.call(() -> provider.search(benchmark.symbol())).stream()
                    .filter(item -> item.symbol().equals(benchmark.symbol()) && Objects.equals(item.currency(), "USD"))
                    .findFirst()
                    .orElse(null);
            if (listing == null) {
                return null;
            }
            fund = new Instrument(null, listing.symbol(), InstrumentType.ETF, listing.currency(), Valuation.MARKET);
            fund.setName(listing.name() != null ? listing.name() : listing.symbol());
            fund.setExchange(listing.exchange());
            fund.setMicCode(listing.micCode());
            fund = instruments.save(fund);
        }
        fund.setBenchmark(true);
        return fund;
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

    private static BigDecimal known(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** The money of the accounts while the days are replayed. */
    private static final class State {

        private final Map<String, BigDecimal> cash = new TreeMap<>();
        private final Map<Instrument, BigDecimal> quantities = new HashMap<>();
        private final Map<Instrument, BigDecimal> lastPrices = new HashMap<>();
        private final List<Deposit> deposits = new ArrayList<>();

        void move(Movement movement, Map<Long, Account> scope) {
            Account source = scope.get(movement.getAccountId());
            Account target = movement.getToAccountId() != null ? scope.get(movement.getToAccountId()) : null;
            if (source != null) {
                BigDecimal change = movement.getType() == TransactionType.INCOME
                        ? movement.getAmount()
                        : movement.getAmount().negate();
                cash.merge(source.getCurrency(), change, BigDecimal::add);
                // Money taken to an account outside: a withdrawal
                if (movement.getType() == TransactionType.TRANSFER && target == null) {
                    deposits.add(new Deposit(movement.getDate(), source.getCurrency(), change));
                }
            }
            if (target != null) {
                cash.merge(target.getCurrency(), movement.getToAmount(), BigDecimal::add);
                if (source == null) {
                    deposits.add(new Deposit(movement.getDate(), target.getCurrency(), movement.getToAmount()));
                }
            }
        }

        void apply(InvestmentOperation operation) {
            cash.merge(operation.getCurrency(), operation.cash(), BigDecimal::add);
            Instrument instrument = operation.getInstrument();
            switch (operation.getType()) {
                case BUY -> {
                    quantities.merge(instrument, operation.getQuantity(), BigDecimal::add);
                    lastPrices.put(instrument, operation.getPrice());
                }
                case SELL -> {
                    quantities.merge(instrument, operation.getQuantity().negate(), BigDecimal::add);
                    lastPrices.put(instrument, operation.getPrice());
                }
                case SPLIT -> {
                    BigDecimal factor = BigDecimal.valueOf(operation.getSplitTo())
                            .divide(BigDecimal.valueOf(operation.getSplitFrom()), PRECISION);
                    quantities.computeIfPresent(instrument, (key, quantity) -> quantity.multiply(factor));
                    lastPrices.computeIfPresent(instrument, (key, price) -> price.divide(factor, PRECISION));
                }
                default -> {}
            }
        }
    }

    /** Money brought to the accounts (negative: taken away). */
    private record Deposit(LocalDate date, String currency, BigDecimal amount) {}

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
     * @param benchmark null without a comparison or when the market data provider is not configured
     * @param points    daily, weekly (counted back from the end) for periods over {@value #MAX_DAILY_POINTS} days
     */
    public record HistoryResponse(
            String currency,
            LocalDate from,
            LocalDate to,
            @Schema(nullable = true) BenchmarkInfo benchmark,
            List<HistoryPoint> points,
            List<String> unconvertedCurrencies) {}
}
