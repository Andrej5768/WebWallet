package com.andrewcorp.webwallet.tax.declaration;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.tax.FopTaxService;
import com.andrewcorp.webwallet.tax.InvestmentTaxService;
import com.andrewcorp.webwallet.tax.TaxDtos.DividendTax;
import com.andrewcorp.webwallet.tax.TaxDtos.FopQuarter;
import com.andrewcorp.webwallet.tax.TaxDtos.FopReport;
import com.andrewcorp.webwallet.tax.TaxDtos.InvestmentTaxReport;
import com.andrewcorp.webwallet.tax.TaxDtos.SaleTax;
import com.andrewcorp.webwallet.tax.TaxRates;
import com.andrewcorp.webwallet.tax.declaration.TaxDocument.Form;
import com.andrewcorp.webwallet.tax.declaration.TaxDocument.Link;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The declarations as XML files of the electronic reporting of the State Tax Service, from the tax reports:
 *
 * <ul>
 *   <li>F0103309, the quarterly declaration of a sole proprietor of group 3 (amounts from the start of the year), with
 *       F0133109, the social contribution of the year, in the fourth quarter;
 *   <li>F0100215, the yearly declaration of property and income, with F0121215 (Ф1), the investment profit: foreign
 *       dividends on line 10.10 with the tax paid abroad on line 18, the investment profit on line 10.8.
 * </ul>
 *
 * The files are meant to be opened in the tax service cabinet, checked and signed there.
 */
@Service
public class DeclarationService {

    static final Form FOP = new Form("F01", "033", 9);
    static final Form FOP_ESV = new Form("F01", "331", 9);
    static final Form PROPERTY = new Form("F01", "002", 15);
    static final Form INVESTMENT_PROFIT = new Form("F01", "212", 15);

    /** The insured person category of a sole proprietor on the simplified system, in the appendix on the contribution. */
    private static final String ESV_CATEGORY = "2";
    /** Securities traded on a stock exchange, in the table of Ф1. */
    private static final String EXCHANGE_TRADED = "2";

    private static final Map<String, String> CURRENCY_COUNTRIES = Map.of(
            "USD", "США, долар США",
            "EUR", "Європейський Союз, євро",
            "GBP", "Велика Британія, фунт стерлінгів",
            "PLN", "Польща, польський злотий",
            "CHF", "Швейцарія, швейцарський франк",
            "CAD", "Канада, канадський долар");

    public record DeclarationFile(String name, byte[] content) {}

    private final TaxProfileService profiles;
    private final FopTaxService fopTaxes;
    private final InvestmentTaxService investmentTaxes;
    private final Clock clock;

    public DeclarationService(
            TaxProfileService profiles, FopTaxService fopTaxes, InvestmentTaxService investmentTaxes, Clock clock) {
        this.profiles = profiles;
        this.fopTaxes = fopTaxes;
        this.investmentTaxes = investmentTaxes;
        this.clock = clock;
    }

    /** The declaration of the sole proprietor for the quarter, and the appendix on the contribution for the fourth. */
    @Transactional(readOnly = true)
    public List<DeclarationFile> fop(Long userId, int year, int quarter) {
        TaxProfileData profile = profile(userId);
        if (profile.kvedCode() == null || profile.kvedName() == null) {
            throw ApiException.invalid("kvedCode", "error.tax.kved");
        }
        FopReport report = fopTaxes.report(userId, year);
        TaxRates rates = report.rates();
        if (rates.fopGroup() != 3) {
            throw ApiException.invalid("fopGroup", "error.tax.group3Only");
        }
        LocalDate today = LocalDate.now(clock);
        int periodType = quarter + 1;
        int periodMonth = quarter * 3;
        boolean esv = quarter == 4 && rates.esvEnabled();
        String mainName = TaxDocument.fileName(FOP, profile, periodType, periodMonth, year);
        String esvName = TaxDocument.fileName(FOP_ESV, profile, periodType, periodMonth, year);

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal before = BigDecimal.ZERO;
        for (FopQuarter row : report.quarters()) {
            if (row.quarter() < quarter) {
                before = before.add(row.income());
            }
            if (row.quarter() <= quarter) {
                income = income.add(row.income());
            }
        }
        BigDecimal rate = rates.fopVat() ? BigDecimal.valueOf(3) : BigDecimal.valueOf(5);
        BigDecimal tax = TaxRates.share(income, rate);
        BigDecimal taxBefore = TaxRates.share(before, rate);
        BigDecimal military = TaxRates.share(income, rates.fopMilitaryRate());
        BigDecimal militaryBefore = TaxRates.share(before, rates.fopMilitaryRate());
        BigDecimal esvMonthly = rates.esvMonthly();

        TaxDocument main = TaxDocument.start(
                FOP,
                profile,
                periodType,
                periodMonth,
                year,
                esv ? List.of(new Link(FOP_ESV, esvName, 1)) : List.of(),
                today);
        main.check("HZ");
        main.check(new String[] {"H1KV", "HHY", "H3KV", "HY"}[quarter - 1]);
        main.text("HZY", String.valueOf(year));
        main.text("HSTI", profile.taxOffice());
        main.text("HNAME", profile.name());
        main.text("HLOC", profile.addressLine());
        main.text("HEMAIL", profile.email());
        main.text("HTEL", profile.phone());
        main.text("HTIN", profile.tin());
        main.text("HNACTL", "0");
        main.row("T1RXXXXG1S", 1, profile.kvedCode());
        main.row("T1RXXXXG2S", 1, profile.kvedName());
        // Income from the start of the year: line 5 at 3 % (a VAT payer), line 6 at 5 %
        main.money(rates.fopVat() ? "R005G3" : "R006G3", income);
        main.money("R008G3", income);
        main.money(rates.fopVat() ? "R010G3" : "R011G3", tax);
        main.money("R012G3", tax);
        main.money("R013G3", taxBefore);
        BigDecimal due = tax.subtract(taxBefore);
        main.money(due.signum() >= 0 ? "R0141G3" : "R0142G3", due.abs());
        main.money("R014G3", due);
        if (esv) {
            main.money("R021G3", esvMonthly.multiply(BigDecimal.valueOf(12)));
        }
        main.money("R023G3", military);
        main.money("R024G3", militaryBefore);
        main.money("R025G3", military.subtract(militaryBefore));
        if (esv) {
            main.check("HD1");
        }
        main.text("HFILL", TaxDocument.date(today));
        main.text("HBOS", profile.name());
        List<DeclarationFile> files = new ArrayList<>();
        files.add(new DeclarationFile(mainName, main.finish()));

        if (esv) {
            TaxDocument appendix = TaxDocument.start(
                    FOP_ESV, profile, periodType, periodMonth, year, List.of(new Link(FOP, mainName, 2)), today);
            appendix.check("HZ");
            appendix.text("HTIN", profile.tin());
            appendix.text("HNAME", profile.name());
            appendix.check("HY");
            appendix.text("HZY", String.valueOf(year));
            appendix.text("HKVED", profile.kvedCode());
            appendix.text("R081G1", ESV_CATEGORY);
            for (int month = 1; month <= 12; month++) {
                appendix.money("R09" + month + "G2", rates.minWage());
                appendix.money("R09" + month + "G3", rates.esvRate());
                appendix.money("R09" + month + "G4", esvMonthly);
            }
            appendix.money("R09G2", rates.minWage().multiply(BigDecimal.valueOf(12)));
            appendix.money("R09G4", esvMonthly.multiply(BigDecimal.valueOf(12)));
            appendix.text("HBOS", profile.name());
            files.add(new DeclarationFile(esvName, appendix.finish()));
        }
        return files;
    }

    /** The yearly declaration of property and income with Ф1 when there were sales. */
    @Transactional(readOnly = true)
    public List<DeclarationFile> property(Long userId, int year) {
        TaxProfileData profile = profile(userId);
        InvestmentTaxReport report = investmentTaxes.report(userId, year);
        TaxRates rates = report.rates();
        LocalDate today = LocalDate.now(clock);
        boolean f1 = !report.sales().isEmpty() || report.carriedIn().signum() > 0;
        String mainName = TaxDocument.fileName(PROPERTY, profile, 5, 12, year);
        String f1Name = TaxDocument.fileName(INVESTMENT_PROFIT, profile, 5, 12, year);

        // Ф1
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (SaleTax sale : report.sales()) {
            income = income.add(sale.incomeUah());
            cost = cost.add(sale.costUah());
        }
        BigDecimal result = income.subtract(cost);
        BigDecimal overall = result.subtract(report.carriedIn());
        BigDecimal profit = overall.max(BigDecimal.ZERO);
        BigDecimal profitPdfo = TaxRates.share(profit, rates.pdfoRate());
        BigDecimal profitMilitary = TaxRates.share(profit, rates.investmentMilitaryRate());

        // Foreign dividends: the income tax on all of them, the tax paid abroad credited on line 18
        BigDecimal dividends = BigDecimal.ZERO;
        BigDecimal dividendPdfo = BigDecimal.ZERO;
        BigDecimal dividendMilitary = BigDecimal.ZERO;
        TreeSet<String> currencies = new TreeSet<>();
        for (DividendTax dividend : report.dividends()) {
            dividends = dividends.add(dividend.amountUah());
            dividendPdfo = dividendPdfo.add(dividend.pdfo().add(dividend.credit()));
            dividendMilitary = dividendMilitary.add(dividend.military());
            currencies.add(dividend.currency());
        }
        BigDecimal credit = report.creditUah().min(dividendPdfo.add(profitPdfo));
        BigDecimal totalIncome = dividends.add(profit);
        BigDecimal pdfo = dividendPdfo.add(profitPdfo);
        BigDecimal military = dividendMilitary.add(profitMilitary);

        TaxDocument main = TaxDocument.start(
                PROPERTY,
                profile,
                5,
                12,
                year,
                f1 ? List.of(new Link(INVESTMENT_PROFIT, f1Name, 1)) : List.of(),
                today);
        main.check("HZ");
        main.text("HZY", String.valueOf(year));
        main.text("HNAME", profile.name());
        main.text("HTIN", profile.tin());
        main.text("HREG", profile.region());
        main.text("HRAJ", profile.district());
        main.text("HCITY", profile.city());
        main.text("HSTREET", profile.street());
        main.text("HBUILD", profile.building());
        main.text("HCORP", profile.corpus());
        main.text("HAPT", profile.apartment());
        main.text("HZIP", profile.zip());
        main.text("HTEL", profile.phone());
        main.text("HEMAIL", profile.email());
        main.text("HSTI", profile.taxOffice());
        // A resident, filling it in personally, of the general category
        main.check("H01");
        main.check("H03");
        main.check("H05");
        main.money("R010G3", totalIncome);
        main.money("R010G6", pdfo);
        main.money("R010G7", military);
        if (f1) {
            main.money("R0108G3", profit);
            main.money("R0108G6", profitPdfo);
            main.money("R0108G7", profitMilitary);
        }
        if (!report.dividends().isEmpty()) {
            main.text("R01010G2S", countries(currencies));
            main.money("R01010G3", dividends);
            main.money("R01010G6", dividendPdfo);
            main.money("R01010G7", dividendMilitary);
        }
        main.money("R012G3", totalIncome);
        main.money("R013G3", pdfo);
        if (credit.signum() > 0) {
            main.money("R018G3", credit);
        }
        main.money("R0201G3", pdfo.subtract(credit).max(BigDecimal.ZERO));
        main.money("R0211G3", military);
        if (f1) {
            main.check("HD1");
        }
        main.text("HFILL", TaxDocument.date(today));
        main.text("HBOS", profile.name());
        List<DeclarationFile> files = new ArrayList<>();
        files.add(new DeclarationFile(mainName, main.finish()));

        if (f1) {
            TaxDocument appendix = TaxDocument.start(
                    INVESTMENT_PROFIT, profile, 5, 12, year, List.of(new Link(PROPERTY, mainName, 2)), today);
            appendix.text("HTIN", profile.tin());
            appendix.check("HZ");
            appendix.text("HZY", String.valueOf(year));
            int row = 1;
            List<SaleTax> sales = new ArrayList<>(report.sales());
            sales.sort((a, b) -> a.date().compareTo(b.date()));
            for (SaleTax sale : sales) {
                appendix.row("T1RXXXXG2", row, EXCHANGE_TRADED);
                appendix.row(
                        "T1RXXXXG3S",
                        row,
                        sale.symbol() + ", "
                                + sale.quantity().stripTrailingZeros().toPlainString() + " шт., "
                                + TaxDocument.date(sale.date()));
                appendix.moneyRow("T1RXXXXG4", row, sale.incomeUah());
                appendix.moneyRow("T1RXXXXG5", row, sale.costUah());
                appendix.moneyRow("T1RXXXXG6", row, sale.resultUah());
                row++;
            }
            appendix.money("R001G4", income);
            appendix.money("R001G5", cost);
            appendix.money("R001G6", result);
            appendix.money("R002G6", report.carriedIn());
            appendix.money("R003G6", overall);
            if (overall.signum() >= 0) {
                appendix.money("R031G6", overall);
            } else {
                appendix.money("R032G6", overall.negate());
            }
            appendix.money("R004G6", profitPdfo);
            appendix.money("R041G6", BigDecimal.ZERO);
            appendix.money("R042G6", profitPdfo);
            appendix.money("R005G6", profitMilitary);
            appendix.money("R051G6", BigDecimal.ZERO);
            appendix.money("R052G6", profitMilitary);
            appendix.text("HBOS", profile.name());
            files.add(new DeclarationFile(f1Name, appendix.finish()));
        }
        return files;
    }

    /** The files in one ZIP, for a download. */
    public static byte[] zip(List<DeclarationFile> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (DeclarationFile file : files) {
                zip.putNextEntry(new ZipEntry(file.name()));
                zip.write(file.content());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private TaxProfileData profile(Long userId) {
        return profiles.find(userId).orElseThrow(() -> ApiException.invalid("profile", "error.tax.profile"));
    }

    private static String countries(TreeSet<String> currencies) {
        List<String> names = new ArrayList<>();
        for (String currency : currencies) {
            names.add(CURRENCY_COUNTRIES.getOrDefault(currency, currency.toUpperCase(Locale.ROOT)));
        }
        return String.join("; ", names);
    }
}
