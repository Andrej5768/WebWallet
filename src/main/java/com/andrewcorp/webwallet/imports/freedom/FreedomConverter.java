package com.andrewcorp.webwallet.imports.freedom;

import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.BrokerOperation;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.BrokerTicker;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.Skip;
import com.andrewcorp.webwallet.imports.freedom.FreedomReport.CashFlow;
import com.andrewcorp.webwallet.imports.freedom.FreedomReport.Trade;
import com.andrewcorp.webwallet.investment.OperationType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns the reports of Freedom Finance into operations of a brokerage account:
 *
 * <ul>
 *   <li>a trade of a security is a purchase or a sale; its fee comes from the cash flows, which name its currency,
 *       and a fee charged in another currency than the trade's becomes a fee operation of its own;
 *   <li>a trade of a currency pair ({@code EUR/USD}) is an exchange;
 *   <li>a dividend takes the tax withheld on the same security and record date;
 *   <li>card payments and bank transfers are deposits and withdrawals; other fees are fees;
 *   <li>blocking and unblocking money for a withdrawal moves nothing and is skipped, as is what is not recognized.
 * </ul>
 */
public final class FreedomConverter {

    static final String BROKER = "Freedom Finance";

    private static final Pattern TRADE_REF = Pattern.compile("\\(\\s*Trade (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADE_CURRENCY =
            Pattern.compile("trade currency:\\s*([A-Za-z]{3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKET = Pattern.compile("Market:\\s*([A-Za-z]+)", Pattern.CASE_INSENSITIVE);
    /**
     * The security and the record date of a dividend or its tax, worded in several ways: "(Schwab US Dividend Equity
     * ETF (SCHD.US)), record date 2026-09-23", "( SPDR … (SPYD.US) ), cut date 2024-09-20", "(SPYD.US), cutoff date
     * 2023-03-20 23:59:59".
     */
    private static final Pattern SECURITY = Pattern.compile(
            "\\(\\s*([A-Za-z0-9._\\-]+)\\s*\\)(?:\\s*\\))?\\s*,?\\s*(?:record|cut|cutoff) date\\s*(\\d{4}-\\d{2}-\\d{2})",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> SUFFIX_CURRENCIES = Map.of("US", "USD", "EU", "EUR", "KZ", "KZT");
    private static final Map<String, String> MARKETS =
            Map.of("usa", "US", "europe", "EU", "kazakhstan", "KZ", "russia", "RU");

    private FreedomConverter() {}

    public static BrokerStatement convert(FreedomReport report) {
        return new Conversion(report).run();
    }

    /** Currency codes as ISO 4217: the reports write RUR for the rouble. */
    static String currency(String code) {
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return upper.equals("RUR") ? "RUB" : upper;
    }

    private static final class Conversion {

        private final FreedomReport report;
        private final List<BrokerOperation> operations = new ArrayList<>();
        private final Map<String, BrokerTicker> tickers = new LinkedHashMap<>();
        /** The market of each ticker, as the fees of its trades name it. */
        private final Map<String, String> markets = new HashMap<>();

        private int merged;

        Conversion(FreedomReport report) {
            this.report = report;
        }

        BrokerStatement run() {
            Map<Long, List<CashFlow>> tradeFees = new HashMap<>();
            List<CashFlow> dividends = new ArrayList<>();
            List<CashFlow> taxes = new ArrayList<>();
            for (CashFlow flow : report.cashFlows()) {
                switch (flow.kind().toLowerCase(Locale.ROOT)) {
                    case "trading fee" -> {
                        Long trade = tradeNumber(flow);
                        if (trade != null) {
                            tradeFees
                                    .computeIfAbsent(trade, number -> new ArrayList<>())
                                    .add(flow);
                        } else {
                            fee(flow);
                        }
                    }
                    case "dividends" -> dividends.add(flow);
                    case "taxes" -> taxes.add(flow);
                    case "negative cash balance fee", "other fees" -> fee(flow);
                    case "card payment", "bank transfer" -> deposit(flow);
                    case "blocking", "unlock" -> skip(flow, Skip.SERVICE);
                    default -> skip(flow, Skip.UNKNOWN);
                }
            }

            Map<String, String> currencies = tradeCurrencies(tradeFees);
            Set<Long> traded = new HashSet<>();
            for (Trade trade : report.trades()) {
                traded.add(trade.number());
                List<CashFlow> fees = tradeFees.getOrDefault(trade.number(), List.of());
                if (trade.ticker().contains("/")) {
                    exchange(trade, fees);
                } else {
                    trade(trade, fees, currencies);
                }
            }
            // Fees of trades the trades report does not have stay fees
            tradeFees.forEach((number, fees) -> {
                if (!traded.contains(number)) {
                    fees.forEach(this::fee);
                }
            });
            dividends(dividends, taxes);

            operations.sort(Comparator.comparing(BrokerOperation::time).thenComparing(BrokerOperation::key));
            return new BrokerStatement(
                    BROKER, mainCurrency(), List.copyOf(operations), List.copyOf(tickers.values()), merged);
        }

        /** The currency of each ticker, as the fees of its trades name it; also fills {@link #markets}. */
        private Map<String, String> tradeCurrencies(Map<Long, List<CashFlow>> tradeFees) {
            Map<String, String> currencies = new HashMap<>();
            for (Trade trade : report.trades()) {
                for (CashFlow fee : tradeFees.getOrDefault(trade.number(), List.of())) {
                    Matcher matcher = TRADE_CURRENCY.matcher(fee.comment());
                    if (matcher.find()) {
                        currencies.putIfAbsent(trade.ticker(), currency(matcher.group(1)));
                    }
                    Matcher market = MARKET.matcher(fee.comment());
                    if (market.find()) {
                        markets.putIfAbsent(
                                trade.ticker(), MARKETS.get(market.group(1).toLowerCase(Locale.ROOT)));
                    }
                }
            }
            return currencies;
        }

        private void trade(Trade trade, List<CashFlow> fees, Map<String, String> currencies) {
            BrokerTicker ticker = ticker(trade.ticker(), currencies.get(trade.ticker()));
            String currency = ticker.currency();
            BigDecimal fee = fee(trade, fees, currency);
            operations.add(new BrokerOperation(
                    "trade:" + trade.number(),
                    trade.time(),
                    trade.buy() ? OperationType.BUY : OperationType.SELL,
                    trade.ticker(),
                    trade.quantity(),
                    trade.price(),
                    trade.amount(),
                    fee,
                    BigDecimal.ZERO,
                    currency,
                    null,
                    null,
                    "Trade " + trade.number(),
                    null));
        }

        /** {@code EUR/USD}: buying gives euros for dollars, selling dollars for euros. */
        private void exchange(Trade trade, List<CashFlow> fees) {
            String[] pair = trade.ticker().split("/");
            if (pair.length != 2) {
                operations.add(skipped("trade:" + trade.number(), trade.time(), trade.ticker(), Skip.UNKNOWN));
                return;
            }
            String base = currency(pair[0]);
            String quote = currency(pair[1]);
            String from = trade.buy() ? quote : base;
            BigDecimal amount = trade.buy() ? trade.amount() : trade.quantity();
            operations.add(new BrokerOperation(
                    "trade:" + trade.number(),
                    trade.time(),
                    OperationType.EXCHANGE,
                    null,
                    null,
                    null,
                    amount,
                    fee(trade, fees, from),
                    BigDecimal.ZERO,
                    from,
                    trade.buy() ? trade.quantity() : trade.amount(),
                    trade.buy() ? base : quote,
                    "Trade " + trade.number() + " " + trade.ticker() + " at "
                            + trade.price().toPlainString(),
                    null));
        }

        /**
         * The fee of a trade in its currency: from the cash flows when they have it, else from the trades report. Fees
         * charged in another currency become fee operations.
         */
        private BigDecimal fee(Trade trade, List<CashFlow> fees, String currency) {
            if (fees.isEmpty()) {
                return trade.fee();
            }
            BigDecimal fee = BigDecimal.ZERO;
            for (CashFlow flow : fees) {
                if (currency(flow.currency()).equals(currency)) {
                    fee = fee.add(flow.amount().negate());
                    merged++;
                } else {
                    fee(flow);
                }
            }
            return fee.max(BigDecimal.ZERO);
        }

        /**
         * Each dividend takes the tax on the same security, in the same currency, of the same record date, else of
         * the same day of payment (the reports do not always repeat the record date the same way).
         */
        private void dividends(List<CashFlow> dividends, List<CashFlow> taxes) {
            List<CashFlow> unpaired = new ArrayList<>(taxes);
            for (CashFlow dividend : dividends) {
                Matcher matcher = SECURITY.matcher(dividend.comment());
                if (!matcher.find() || dividend.amount().signum() <= 0) {
                    skip(dividend, Skip.UNKNOWN);
                    continue;
                }
                String currency = currency(dividend.currency());
                CashFlow taxRow = taxOf(dividend, matcher.group(1), matcher.group(2), unpaired);
                BigDecimal tax = BigDecimal.ZERO;
                if (taxRow != null) {
                    unpaired.remove(taxRow);
                    tax = taxRow.amount().negate().max(BigDecimal.ZERO);
                    merged++;
                }
                BrokerTicker ticker = ticker(matcher.group(1), currency);
                operations.add(new BrokerOperation(
                        "cash:" + dividend.number(),
                        dividend.time(),
                        OperationType.DIVIDEND,
                        ticker.ticker(),
                        null,
                        null,
                        dividend.amount(),
                        BigDecimal.ZERO,
                        tax.min(dividend.amount()),
                        currency,
                        null,
                        null,
                        description(dividend),
                        null));
            }
            // Taxes without their dividend are charges of their own
            unpaired.forEach(this::fee);
        }

        private static CashFlow taxOf(CashFlow dividend, String security, String recordDate, List<CashFlow> taxes) {
            CashFlow sameDay = null;
            for (CashFlow tax : taxes) {
                Matcher matcher = SECURITY.matcher(tax.comment());
                if (!matcher.find()
                        || !matcher.group(1).equalsIgnoreCase(security)
                        || !currency(tax.currency()).equals(currency(dividend.currency()))) {
                    continue;
                }
                if (matcher.group(2).equals(recordDate)) {
                    return tax;
                }
                if (sameDay == null
                        && tax.time().toLocalDate().equals(dividend.time().toLocalDate())) {
                    sameDay = tax;
                }
            }
            return sameDay;
        }

        private void fee(CashFlow flow) {
            if (flow.amount().signum() >= 0) {
                // A refund: not a fee, and not a deposit either
                skip(flow, Skip.UNKNOWN);
                return;
            }
            operations.add(money(flow, OperationType.FEE, flow.amount().negate()));
        }

        private void deposit(CashFlow flow) {
            if (flow.amount().signum() == 0) {
                skip(flow, Skip.SERVICE);
                return;
            }
            operations.add(
                    flow.amount().signum() > 0
                            ? money(flow, OperationType.DEPOSIT, flow.amount())
                            : money(
                                    flow,
                                    OperationType.WITHDRAWAL,
                                    flow.amount().negate()));
        }

        private BrokerOperation money(CashFlow flow, OperationType type, BigDecimal amount) {
            return new BrokerOperation(
                    "cash:" + flow.number(),
                    flow.time(),
                    type,
                    null,
                    null,
                    null,
                    amount,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    currency(flow.currency()),
                    null,
                    null,
                    description(flow),
                    null);
        }

        private void skip(CashFlow flow, Skip skip) {
            operations.add(new BrokerOperation(
                    "cash:" + flow.number(),
                    flow.time(),
                    null,
                    null,
                    null,
                    null,
                    flow.amount(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    currency(flow.currency()),
                    null,
                    null,
                    flow.kind() + ": " + description(flow),
                    skip));
        }

        private static BrokerOperation skipped(String key, LocalDateTime time, String text, Skip skip) {
            return new BrokerOperation(
                    key,
                    time,
                    null,
                    null,
                    null,
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "",
                    null,
                    null,
                    text,
                    skip);
        }

        private BrokerTicker ticker(String name, String knownCurrency) {
            String ticker = name.trim().toUpperCase(Locale.ROOT);
            return tickers.computeIfAbsent(ticker, key -> {
                int dot = key.lastIndexOf('.');
                String suffix = dot > 0 && key.length() - dot == 3 ? key.substring(dot + 1) : null;
                String symbol = suffix != null ? key.substring(0, dot) : key;
                // Tickers without a suffix are those of the Moscow exchange, in roubles
                String market = suffix != null ? suffix : markets.getOrDefault(name, "RU");
                String currency = knownCurrency != null
                        ? knownCurrency
                        : SUFFIX_CURRENCIES.containsKey(market)
                                ? SUFFIX_CURRENCIES.get(market)
                                : market.equals("RU") ? "RUB" : mainCurrency();
                return new BrokerTicker(key, symbol, market, currency);
            });
        }

        private Long tradeNumber(CashFlow flow) {
            Matcher matcher = TRADE_REF.matcher(flow.comment());
            return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
        }

        /** The currency most cash flows are in; dollars without any. */
        private String mainCurrency() {
            return report.cashFlows().stream()
                    .collect(Collectors.groupingBy(flow -> currency(flow.currency()), Collectors.counting()))
                    .entrySet()
                    .stream()
                    .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey)
                    .orElse("USD");
        }

        private static String description(CashFlow flow) {
            return Texts.truncate(flow.comment(), 1000);
        }
    }
}
