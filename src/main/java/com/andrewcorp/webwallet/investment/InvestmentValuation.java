package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.currency.ConverterCache;
import com.andrewcorp.webwallet.currency.Converters;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the investment operations of a user hold on their brokerage accounts: the cash they moved per currency and the
 * positions with their lots and prices. The money brought to an account by transfers is in the ledger, not here.
 */
@Component
public class InvestmentValuation {

    private static final Logger log = LoggerFactory.getLogger(InvestmentValuation.class);

    private final InvestmentOperationRepository operations;
    private final InstrumentPriceRepository prices;
    private final Converters converters;

    public InvestmentValuation(
            InvestmentOperationRepository operations, InstrumentPriceRepository prices, Converters converters) {
        this.operations = operations;
        this.prices = prices;
        this.converters = converters;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(Long userId, LocalDate date) {
        List<InvestmentOperation> all = operations.findAllOfUser(userId).stream()
                .filter(operation -> !operation.getDate().isAfter(date))
                .toList();
        Map<Long, Map<String, BigDecimal>> cash = new TreeMap<>();
        Map<Long, Map<Instrument, List<InvestmentOperation>>> byInstrument = new TreeMap<>();
        for (InvestmentOperation operation : all) {
            Long accountId = operation.getAccount().getId();
            cash.computeIfAbsent(accountId, id -> new TreeMap<>())
                    .merge(operation.getCurrency(), operation.cash(), BigDecimal::add);
            if (operation.getInstrument() != null) {
                byInstrument
                        .computeIfAbsent(accountId, id -> new LinkedHashMap<>())
                        .computeIfAbsent(operation.getInstrument(), instrument -> new ArrayList<>())
                        .add(operation);
            }
        }
        Set<Long> marketIds = all.stream()
                .map(InvestmentOperation::getInstrument)
                .filter(instrument -> instrument != null && instrument.getValuation() == Valuation.MARKET)
                .map(Instrument::getId)
                .collect(Collectors.toSet());
        Map<Long, List<InstrumentPrice>> latest = marketIds.isEmpty()
                ? Map.of()
                : prices.findLatest(marketIds, date).stream()
                        .collect(Collectors.groupingBy(InstrumentPrice::getInstrumentId));

        Map<Long, AccountHoldings> accounts = new TreeMap<>();
        Set<Long> accountIds = new TreeSet<>(cash.keySet());
        accountIds.addAll(byInstrument.keySet());
        for (Long accountId : accountIds) {
            List<Holding> holdings = new ArrayList<>();
            byInstrument
                    .getOrDefault(accountId, Map.of())
                    .forEach((instrument, list) -> holdings.add(
                            new Holding(instrument, lots(accountId, list), list, price(instrument, latest, date))));
            accounts.put(accountId, new AccountHoldings(accountId, cash.getOrDefault(accountId, Map.of()), holdings));
        }
        return new Snapshot(accounts);
    }

    /**
     * What the investment operations add to the balance of each account, in the currency of the account at the rates
     * of the date: their cash and the value of the positions. Amounts without a rate are left out.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> balances(Long userId, Map<Long, String> accountCurrencies, LocalDate date) {
        Map<Long, BigDecimal> result = new HashMap<>();
        ConverterCache cache = converters.cache();
        for (AccountHoldings account : snapshot(userId, date).accounts().values()) {
            String currency = accountCurrencies.get(account.accountId());
            if (currency == null) {
                continue;
            }
            CurrencyConverter converter = cache.to(currency);
            BigDecimal total = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> cash : account.cash().entrySet()) {
                total = total.add(converter.convert(cash.getValue(), cash.getKey(), date));
            }
            for (Holding holding : account.holdings()) {
                total = total.add(
                        converter.convert(holding.value(), holding.instrument().getCurrency(), date));
            }
            result.put(account.accountId(), total);
        }
        return result;
    }

    /**
     * The lots of an instrument. Operations stored before the check, or by two sales at once, may sell more than was
     * held: they are valued as far as they go rather than making the accounts unreadable.
     */
    private static LotLedger lots(Long accountId, List<InvestmentOperation> list) {
        try {
            return LotLedger.of(list);
        } catch (LotLedger.Shortfall e) {
            log.warn("Account {} sold more than it held on {}", accountId, e.date());
            return LotLedger.lenient(list);
        }
    }

    private Price price(Instrument instrument, Map<Long, List<InstrumentPrice>> latest, LocalDate date) {
        return switch (instrument.getValuation()) {
            case MARKET -> {
                List<InstrumentPrice> closes = latest.getOrDefault(instrument.getId(), List.of());
                if (closes.isEmpty()) {
                    yield null;
                }
                yield new Price(
                        closes.get(0).getClose(),
                        closes.get(0).getDate(),
                        closes.size() > 1 ? closes.get(1).getClose() : null);
            }
            case NOMINAL -> {
                BigDecimal price = BondValuation.price(instrument, date);
                yield price != null ? new Price(price, date, null) : null;
            }
            case MANUAL ->
                instrument.getManualPrice() != null
                        ? new Price(instrument.getManualPrice(), instrument.getManualPriceDate(), null)
                        : null;
        };
    }

    /** @param accounts the brokerage accounts with operations, by id */
    public record Snapshot(Map<Long, AccountHoldings> accounts) {}

    /** @param cash what the operations moved per currency: positive is money on the account */
    public record AccountHoldings(Long accountId, Map<String, BigDecimal> cash, List<Holding> holdings) {}

    /**
     * An instrument on an account, also one sold off.
     *
     * @param price null when no price is known yet; the position is then valued at its cost
     */
    public record Holding(Instrument instrument, LotLedger lots, List<InvestmentOperation> operations, Price price) {

        public boolean open() {
            return lots.quantity().signum() > 0;
        }

        /** Value in the currency of the instrument. */
        public BigDecimal value() {
            if (price == null) {
                return lots.cost();
            }
            return lots.quantity().multiply(price.close()).setScale(4, RoundingMode.HALF_UP);
        }
    }

    /**
     * @param date          of the close (or of the manual price, which may be unknown)
     * @param previousClose the close of the trading day before, for the change of the day
     */
    public record Price(BigDecimal close, LocalDate date, BigDecimal previousClose) {}
}
