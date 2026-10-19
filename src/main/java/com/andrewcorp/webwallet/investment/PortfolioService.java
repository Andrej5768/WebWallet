package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.util.Money;
import com.andrewcorp.webwallet.currency.Converters;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.investment.Deposits.Deposit;
import com.andrewcorp.webwallet.investment.HistoryRepository.Movement;
import com.andrewcorp.webwallet.investment.InvestmentDtos.AllocationPart;
import com.andrewcorp.webwallet.investment.InvestmentDtos.CashResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.LotResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.OperationResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.PortfolioResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.PositionDetailResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.PositionResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.SalePart;
import com.andrewcorp.webwallet.investment.InvestmentDtos.SaleResponse;
import com.andrewcorp.webwallet.investment.InvestmentValuation.AccountHoldings;
import com.andrewcorp.webwallet.investment.InvestmentValuation.Holding;
import com.andrewcorp.webwallet.investment.InvestmentValuation.Snapshot;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The portfolio of the brokerage accounts and the details of one position. */
@Service
public class PortfolioService {

    static final String CASH = "CASH";

    private final Converters converters;
    private final InvestmentValuation valuation;
    private final InstrumentService instrumentService;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final InvestmentAccounts investmentAccounts;
    private final Deposits deposits;
    private final InvestmentOperationRepository operations;
    private final Clock clock;

    public PortfolioService(
            Converters converters,
            InvestmentValuation valuation,
            InstrumentService instrumentService,
            AccountRepository accounts,
            AccountService accountService,
            InvestmentAccounts investmentAccounts,
            Deposits deposits,
            InvestmentOperationRepository operations,
            Clock clock) {
        this.converters = converters;
        this.valuation = valuation;
        this.instrumentService = instrumentService;
        this.accounts = accounts;
        this.accountService = accountService;
        this.investmentAccounts = investmentAccounts;
        this.deposits = deposits;
        this.operations = operations;
        this.clock = clock;
    }

    /**
     * The portfolio of all brokerage accounts that are not archived, or of one, in the currency (by default the base
     * one).
     */
    @Transactional(readOnly = true)
    public PortfolioResponse portfolio(Long userId, Long accountId, String currency) {
        LocalDate today = LocalDate.now(clock);
        CurrencyConverter converter = converters.to(userId, currency);
        Map<Long, Account> brokerage = investmentAccounts.scope(userId, accountId);
        Snapshot snapshot = valuation.snapshot(userId, today);
        BigDecimal deposited = deposited(userId, brokerage, converter, today);
        Map<Long, BigDecimal> ledger = accountService.ledgerBalances(userId);

        List<PositionDraft> positions = new ArrayList<>();
        List<CashResponse> cash = new ArrayList<>();
        Map<String, BigDecimal> byType = new TreeMap<>();
        Map<String, BigDecimal> byCurrency = new TreeMap<>();
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal dayChange = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal dividends = BigDecimal.ZERO;
        LocalDate pricesAsOf = null;
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate dividendsFrom = today.minusYears(1).plusDays(1);

        for (Account account : brokerage.values()) {
            AccountHoldings holdings = snapshot.accounts().get(account.getId());
            Map<String, BigDecimal> money = new TreeMap<>();
            money.put(account.getCurrency(), ledger.getOrDefault(account.getId(), account.getOpeningBalance()));
            if (holdings != null) {
                holdings.cash().forEach((code, amount) -> money.merge(code, amount, BigDecimal::add));
                for (Holding holding : holdings.holdings()) {
                    Instrument instrument = holding.instrument();
                    for (LotLedger.Sale sale : holding.lots().sales()) {
                        if (!sale.date().isBefore(yearStart)) {
                            realized = realized.add(
                                    converter.convert(sale.realized(), instrument.getCurrency(), sale.date()));
                        }
                    }
                    for (InvestmentOperation operation : holding.operations()) {
                        boolean income = operation.getType() == OperationType.DIVIDEND
                                || operation.getType() == OperationType.COUPON;
                        if (income && !operation.getDate().isBefore(dividendsFrom)) {
                            dividends = dividends.add(
                                    converter.convert(operation.cash(), operation.getCurrency(), operation.getDate()));
                        }
                    }
                    if (!holding.open()) {
                        continue;
                    }
                    BigDecimal valueInBase = converter.convertIfKnown(holding.value(), instrument.getCurrency(), today);
                    positions.add(new PositionDraft(account.getId(), holding, valueInBase));
                    if (valueInBase != null) {
                        byType.merge(instrument.getType().name(), valueInBase, BigDecimal::add);
                        byCurrency.merge(instrument.getCurrency(), valueInBase, BigDecimal::add);
                    }
                    BigDecimal cost = converter.convertIfKnown(holding.lots().cost(), instrument.getCurrency(), today);
                    if (cost != null) {
                        invested = invested.add(cost);
                    }
                    InvestmentValuation.Price price = holding.price();
                    if (price != null && price.previousClose() != null) {
                        BigDecimal change =
                                holding.lots().quantity().multiply(price.close().subtract(price.previousClose()));
                        BigDecimal changeInBase = converter.convertIfKnown(change, instrument.getCurrency(), today);
                        if (changeInBase != null) {
                            dayChange = dayChange.add(changeInBase);
                        }
                    }
                    if (price != null && instrument.getValuation() == Valuation.MARKET) {
                        pricesAsOf = pricesAsOf == null || price.date().isAfter(pricesAsOf) ? price.date() : pricesAsOf;
                    }
                }
            }
            for (Map.Entry<String, BigDecimal> entry : money.entrySet()) {
                if (entry.getValue().signum() == 0 && !entry.getKey().equals(account.getCurrency())) {
                    continue;
                }
                BigDecimal inBase = converter.convertIfKnown(entry.getValue(), entry.getKey(), today);
                cash.add(new CashResponse(account.getId(), entry.getKey(), entry.getValue(), inBase));
                if (inBase != null) {
                    byType.merge(CASH, inBase, BigDecimal::add);
                    byCurrency.merge(entry.getKey(), inBase, BigDecimal::add);
                }
            }
        }

        BigDecimal value = byType.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal positionsValue = positions.stream()
                .map(PositionDraft::valueInBase)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unrealized = positionsValue.subtract(invested);
        List<PositionResponse> positionResponses = positions.stream()
                .map(draft -> position(draft, value))
                .sorted(Comparator.comparing((PositionResponse position) ->
                                position.valueInBase() != null ? position.valueInBase() : BigDecimal.ZERO)
                        .reversed())
                .toList();
        return new PortfolioResponse(
                converter.target(),
                value,
                deposited,
                invested,
                unrealized,
                Money.share(unrealized, invested),
                realized,
                dividends,
                dayChange,
                pricesAsOf,
                positionResponses,
                cash,
                allocation(byType, value),
                allocation(byCurrency, value),
                converter.unconverted());
    }

    /**
     * The money brought to the accounts minus the money taken away, at the rates of their dates; the opening balances
     * count on the first day with money on the accounts.
     */
    private BigDecimal deposited(Long userId, Map<Long, Account> scope, CurrencyConverter converter, LocalDate today) {
        List<Movement> movements = deposits.movements(scope);
        LocalDate first = movements.isEmpty() ? today : movements.get(0).getDate();
        for (InvestmentOperation operation : operations.findAllOfUser(userId)) {
            if (scope.containsKey(operation.getAccount().getId())
                    && operation.getDate().isBefore(first)) {
                first = operation.getDate();
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Deposit deposit : Deposits.of(scope, movements, first)) {
            total = total.add(converter.convert(deposit.amount(), deposit.currency(), deposit.date()));
        }
        return Money.round2(total);
    }

    /** One instrument on one account: lots, sales and operations. */
    @Transactional(readOnly = true)
    public PositionDetailResponse position(Long userId, Long accountId, Long instrumentId) {
        Instrument instrument = instrumentService.find(userId, instrumentId);
        Account account = accounts.findByIdAndUserId(accountId, userId).orElseThrow(ApiException::notFound);
        Holding holding = valuation
                .snapshot(userId, LocalDate.now(clock))
                .accounts()
                .getOrDefault(account.getId(), new AccountHoldings(account.getId(), Map.of(), List.of()))
                .holdings()
                .stream()
                .filter(item -> item.instrument().getId().equals(instrument.getId()))
                .findFirst()
                .orElseThrow(ApiException::notFound);
        LotLedger lots = holding.lots();
        BigDecimal price = holding.price() != null ? holding.price().close() : null;
        BigDecimal realized =
                lots.sales().stream().map(LotLedger.Sale::realized).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal dividends = holding.operations().stream()
                .filter(operation ->
                        operation.getType() == OperationType.DIVIDEND || operation.getType() == OperationType.COUPON)
                .map(InvestmentOperation::cash)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PositionDetailResponse(
                instrumentService.response(instrument),
                account.getId(),
                lots.quantity(),
                lots.averagePrice(),
                price,
                holding.value(),
                lots.cost(),
                holding.value().subtract(lots.cost()),
                realized,
                dividends,
                lots.lots().stream()
                        .map(lot -> new LotResponse(
                                lot.operationId(),
                                lot.date(),
                                lot.quantity(),
                                lot.remaining(),
                                lot.unitCost().setScale(6, RoundingMode.HALF_UP),
                                price != null && lot.remaining().signum() > 0
                                        ? lot.remaining()
                                                .multiply(price.subtract(lot.unitCost()))
                                                .setScale(4, RoundingMode.HALF_UP)
                                        : null))
                        .toList(),
                lots.sales().stream()
                        .map(sale -> new SaleResponse(
                                sale.operationId(),
                                sale.date(),
                                sale.quantity(),
                                sale.proceeds(),
                                sale.cost(),
                                sale.realized(),
                                sale.parts().stream()
                                        .map(part -> new SalePart(
                                                part.lotOperationId(),
                                                part.lotDate(),
                                                part.quantity(),
                                                part.unitCost().setScale(6, RoundingMode.HALF_UP)))
                                        .toList()))
                        .toList(),
                holding.operations().stream()
                        .sorted(Comparator.comparing(InvestmentOperation::getDate)
                                .thenComparing(InvestmentOperation::getId)
                                .reversed())
                        .map(OperationResponse::from)
                        .toList());
    }

    private PositionResponse position(PositionDraft draft, BigDecimal total) {
        Holding holding = draft.holding();
        LotLedger lots = holding.lots();
        InvestmentValuation.Price price = holding.price();
        BigDecimal unrealized = holding.value().subtract(lots.cost());
        return new PositionResponse(
                draft.accountId(),
                instrumentService.response(holding.instrument()),
                lots.quantity(),
                lots.averagePrice(),
                price != null ? price.close() : null,
                price != null ? price.date() : null,
                price != null && price.previousClose() != null ? price.close().subtract(price.previousClose()) : null,
                holding.value(),
                lots.cost(),
                unrealized,
                Money.share(unrealized, lots.cost()),
                draft.valueInBase(),
                draft.valueInBase() != null ? Money.share(draft.valueInBase(), total) : null);
    }

    private static List<AllocationPart> allocation(Map<String, BigDecimal> parts, BigDecimal total) {
        return parts.entrySet().stream()
                .filter(entry -> entry.getValue().signum() != 0)
                .map(entry ->
                        new AllocationPart(entry.getKey(), entry.getValue(), Money.share(entry.getValue(), total)))
                .sorted(Comparator.comparing(AllocationPart::value).reversed())
                .toList();
    }

    private record PositionDraft(Long accountId, Holding holding, BigDecimal valueInBase) {}
}
