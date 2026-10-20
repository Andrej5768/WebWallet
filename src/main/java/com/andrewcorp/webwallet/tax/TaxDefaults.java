package com.andrewcorp.webwallet.tax;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The default tax rates of a year, as the law set them when the year was added here. They change: every value can be
 * replaced by the user, and {@link #SOURCES} point to where the current ones are published. Years after the last
 * known one take its values.
 */
public final class TaxDefaults {

    /** Where the rates are set. */
    public static final Map<String, String> SOURCES = Map.of(
            "taxCode", "https://zakon.rada.gov.ua/laws/show/2755-17",
            "esv", "https://zakon.rada.gov.ua/laws/show/2464-17",
            "taxService", "https://tax.gov.ua/");

    /** The minimum wage and the subsistence minimum of able-bodied persons on 1 January. */
    private record Year(
            int year, String minWage, String subsistence, String fopMilitaryRate, String investmentMilitary) {}

    private static final Year[] YEARS = {
        // The military levy of group 3 started in 2025; on investment income it grew from 1.5 % on 1 December 2024
        new Year(2024, "7100", "3028", "0", "1.5"),
        new Year(2025, "8000", "3028", "1", "5"),
        new Year(2026, "8647", "3328", "1", "5"),
    };

    private TaxDefaults() {}

    /** The defaults of the year for the group of the sole proprietor and whether it pays VAT. */
    public static TaxRates of(int year, int fopGroup, boolean fopVat) {
        Year known = YEARS[0];
        for (Year candidate : YEARS) {
            if (candidate.year() <= year) {
                known = candidate;
            }
        }
        BigDecimal minWage = new BigDecimal(known.minWage());
        BigDecimal militaryMonthly = known.fopMilitaryRate().equals("0")
                ? BigDecimal.ZERO
                // Groups 1 and 2: 10 % of the minimum wage a month
                : TaxRates.share(minWage, BigDecimal.TEN);
        BigDecimal singleMonthly = fopGroup == 1
                // Group 1: up to 10 % of the subsistence minimum; group 2: up to 20 % of the minimum wage
                ? TaxRates.share(new BigDecimal(known.subsistence()), BigDecimal.TEN)
                : TaxRates.share(minWage, BigDecimal.valueOf(20));
        return new TaxRates(
                fopGroup,
                fopVat,
                fopVat ? BigDecimal.valueOf(3) : BigDecimal.valueOf(5),
                new BigDecimal(known.fopMilitaryRate()),
                singleMonthly,
                militaryMonthly,
                true,
                BigDecimal.valueOf(22),
                minWage,
                BigDecimal.valueOf(18),
                new BigDecimal(known.investmentMilitary()),
                false);
    }
}
