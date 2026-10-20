package com.andrewcorp.webwallet.tax;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.currency.CurrencyConverter;
import com.andrewcorp.webwallet.currency.ExchangeRateService;
import com.andrewcorp.webwallet.investment.Instrument;
import com.andrewcorp.webwallet.investment.InstrumentType;
import com.andrewcorp.webwallet.investment.InvestmentOperation;
import com.andrewcorp.webwallet.investment.InvestmentOperationRepository;
import com.andrewcorp.webwallet.investment.LotLedger;
import com.andrewcorp.webwallet.investment.OperationType;
import com.andrewcorp.webwallet.tax.TaxDtos.DividendTax;
import com.andrewcorp.webwallet.tax.TaxDtos.InvestmentTaxReport;
import com.andrewcorp.webwallet.tax.TaxDtos.SaleTax;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The taxes of investment income: personal income tax and military levy on dividends and coupons and on the profit
 * of the sales of a year, in hryvnias at the NBU rates of the dates of the operations. The tax withheld abroad from a
 * dividend reduces the personal income tax when the user has the document of it. A yearly loss of the sales carries
 * over to the following years. Government bonds are exempt.
 */
@Service
public class InvestmentTaxService {

    private final TaxSettingsService settings;
    private final InvestmentOperationRepository operations;
    private final ExchangeRateService exchangeRates;

    public InvestmentTaxService(
            TaxSettingsService settings, InvestmentOperationRepository operations, ExchangeRateService exchangeRates) {
        this.settings = settings;
        this.operations = operations;
        this.exchangeRates = exchangeRates;
    }

    @Transactional(readOnly = true)
    public InvestmentTaxReport report(Long userId, int year) {
        TaxRates rates = settings.rates(userId, year);
        CurrencyConverter converter = exchangeRates.converterTo(ExchangeRateService.UAH);
        List<InvestmentOperation> all = operations.findAllOfUser(userId);

        List<DividendTax> dividends = new ArrayList<>();
        BigDecimal dividendsUah = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal pdfo = BigDecimal.ZERO;
        BigDecimal military = BigDecimal.ZERO;
        for (InvestmentOperation operation : all) {
            boolean income =
                    operation.getType() == OperationType.DIVIDEND || operation.getType() == OperationType.COUPON;
            if (!income || operation.getDate().getYear() != year || exempt(operation.getInstrument())) {
                continue;
            }
            DividendTax dividend = dividend(operation, rates, converter);
            dividends.add(dividend);
            dividendsUah = dividendsUah.add(dividend.amountUah());
            credit = credit.add(dividend.credit());
            pdfo = pdfo.add(dividend.pdfo());
            military = military.add(dividend.military());
        }
        dividends.sort(Comparator.comparing(DividendTax::date)
                .thenComparing(DividendTax::operationId)
                .reversed());

        // Every sale of every year: the loss of earlier years carries over
        Map<Integer, BigDecimal> resultByYear = new TreeMap<>();
        List<SaleTax> sales = new ArrayList<>();
        for (List<InvestmentOperation> lot : byPosition(all).values()) {
            Instrument instrument = lot.get(0).getInstrument();
            if (exempt(instrument)) {
                continue;
            }
            for (LotLedger.Sale sale : LotLedger.lenient(lot).sales()) {
                SaleTax tax = sale(sale, lot.get(0), converter);
                resultByYear.merge(sale.date().getYear(), tax.resultUah(), BigDecimal::add);
                if (sale.date().getYear() == year) {
                    sales.add(tax);
                }
            }
        }
        sales.sort(Comparator.comparing(SaleTax::date)
                .thenComparing(SaleTax::operationId)
                .reversed());
        BigDecimal carried = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> entry : resultByYear.entrySet()) {
            if (entry.getKey() >= year) {
                break;
            }
            carried = carriedAfter(carried, entry.getValue());
        }
        BigDecimal profit = resultByYear.getOrDefault(year, BigDecimal.ZERO);
        BigDecimal taxable = profit.subtract(carried).max(BigDecimal.ZERO);
        BigDecimal carriedOut = carriedAfter(carried, profit);
        pdfo = pdfo.add(TaxRates.share(taxable, rates.pdfoRate()));
        military = military.add(TaxRates.share(taxable, rates.investmentMilitaryRate()));

        return new InvestmentTaxReport(
                year,
                rates,
                dividends,
                sales,
                money(dividendsUah),
                money(credit),
                money(profit),
                money(carried),
                money(taxable),
                money(carriedOut),
                money(pdfo),
                money(military),
                money(pdfo.add(military)),
                LocalDate.of(year + 1, 5, 1),
                LocalDate.of(year + 1, 7, 31),
                converter.unconverted());
    }

    /** Sets whether the document of the tax withheld abroad from a dividend exists; null takes the default. */
    @Transactional
    public void setDocument(Long userId, Long operationId, Boolean document) {
        InvestmentOperation operation =
                operations.findByIdAndUserId(operationId, userId).orElseThrow(ApiException::notFound);
        if (operation.getType() != OperationType.DIVIDEND && operation.getType() != OperationType.COUPON) {
            throw ApiException.invalid("operationId", "error.tax.notDividend");
        }
        operation.setForeignTaxDocument(document);
    }

    /** The loss left after a year: a profit uses it up, a loss adds to it. */
    private static BigDecimal carriedAfter(BigDecimal carried, BigDecimal result) {
        return result.signum() < 0
                ? carried.add(result.negate())
                : carried.subtract(result).max(BigDecimal.ZERO);
    }

    private static boolean exempt(Instrument instrument) {
        return instrument != null && instrument.getType() == InstrumentType.GOVERNMENT_BOND;
    }

    private static DividendTax dividend(InvestmentOperation operation, TaxRates rates, CurrencyConverter converter) {
        String currency = operation.getCurrency();
        BigDecimal rate = rate(currency, operation.getDate(), converter);
        BigDecimal amountUah = uah(operation.getAmount(), rate);
        BigDecimal withheldUah = uah(operation.getTax(), rate);
        boolean documentSet = operation.getForeignTaxDocument() != null;
        boolean document = documentSet ? operation.getForeignTaxDocument() : rates.foreignDocumentDefault();
        BigDecimal fullPdfo = TaxRates.share(amountUah, rates.pdfoRate());
        BigDecimal credit = document ? withheldUah.min(fullPdfo) : BigDecimal.ZERO;
        Instrument instrument = operation.getInstrument();
        return new DividendTax(
                operation.getId(),
                operation.getDate(),
                operation.getAccount().getId(),
                instrument != null ? instrument.getSymbol() : "",
                operation.getType().name(),
                operation.getAmount(),
                operation.getTax(),
                currency,
                currency.equals(ExchangeRateService.UAH) ? null : rate,
                amountUah,
                withheldUah,
                document,
                documentSet,
                credit,
                fullPdfo.subtract(credit),
                TaxRates.share(amountUah, rates.investmentMilitaryRate()));
    }

    private static SaleTax sale(LotLedger.Sale sale, InvestmentOperation any, CurrencyConverter converter) {
        String currency = any.getCurrency();
        BigDecimal incomeUah = uah(sale.proceeds(), rate(currency, sale.date(), converter));
        BigDecimal costUah = BigDecimal.ZERO;
        for (LotLedger.Part part : sale.parts()) {
            costUah = costUah.add(
                    uah(part.quantity().multiply(part.unitCost()), rate(currency, part.lotDate(), converter)));
        }
        return new SaleTax(
                sale.operationId(),
                sale.date(),
                any.getAccount().getId(),
                any.getInstrument().getSymbol(),
                sale.quantity(),
                currency,
                sale.proceeds(),
                sale.cost(),
                incomeUah,
                money(costUah),
                incomeUah.subtract(money(costUah)),
                sale.realized());
    }

    /** The trades and splits of each instrument on each account, which make its lots. */
    private static Map<String, List<InvestmentOperation>> byPosition(List<InvestmentOperation> all) {
        Map<String, List<InvestmentOperation>> positions = new LinkedHashMap<>();
        for (InvestmentOperation operation : all) {
            OperationType type = operation.getType();
            if (type == OperationType.BUY || type == OperationType.SELL || type == OperationType.SPLIT) {
                positions
                        .computeIfAbsent(
                                operation.getAccount().getId() + ":"
                                        + operation.getInstrument().getId(),
                                key -> new ArrayList<>())
                        .add(operation);
            }
        }
        return positions;
    }

    /** Hryvnias for one unit of the currency on the date; zero when unknown (reported as unconverted). */
    private static BigDecimal rate(String currency, LocalDate date, CurrencyConverter converter) {
        BigDecimal rate = converter.convertIfKnown(BigDecimal.ONE, currency, date);
        return rate != null ? rate : BigDecimal.ZERO;
    }

    private static BigDecimal uah(BigDecimal amount, BigDecimal rate) {
        return money(amount.multiply(rate));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
