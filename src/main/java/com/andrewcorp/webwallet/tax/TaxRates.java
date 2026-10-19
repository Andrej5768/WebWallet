package com.andrewcorp.webwallet.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The tax rates of a year as they apply: the user's own values, else the defaults of the year. Rates are in percent.
 *
 * @param fopGroup               group of the sole proprietor (ФОП): 1, 2 or 3
 * @param fopVat                 a group 3 VAT payer, with the lower single tax rate
 * @param singleTaxRate          group 3: the single tax, a share of the income
 * @param fopMilitaryRate        group 3: the military levy, a share of the income
 * @param singleTaxMonthly       groups 1 and 2: the single tax a month
 * @param fopMilitaryMonthly     groups 1 and 2: the military levy a month
 * @param esvEnabled             whether the minimum social contribution is paid
 * @param esvRate                the social contribution, a share of the minimum wage a month
 * @param minWage                the minimum wage on 1 January, which the contribution and the income limit follow
 * @param pdfoRate               the personal income tax on dividends and investment profit
 * @param investmentMilitaryRate the military levy on dividends and investment profit
 * @param foreignDocumentDefault whether a dividend without its own mark has the document of the tax withheld abroad
 */
public record TaxRates(
        int fopGroup,
        boolean fopVat,
        BigDecimal singleTaxRate,
        BigDecimal fopMilitaryRate,
        BigDecimal singleTaxMonthly,
        BigDecimal fopMilitaryMonthly,
        boolean esvEnabled,
        BigDecimal esvRate,
        BigDecimal minWage,
        BigDecimal pdfoRate,
        BigDecimal investmentMilitaryRate,
        boolean foreignDocumentDefault) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The social contribution a month, or zero when it is not paid. */
    public BigDecimal esvMonthly() {
        return esvEnabled ? share(minWage, esvRate) : BigDecimal.ZERO;
    }

    /** The yearly income limit of the group: 167, 834 or 1167 minimum wages. */
    public BigDecimal incomeLimit() {
        int wages = switch (fopGroup) {
            case 1 -> 167;
            case 2 -> 834;
            default -> 1167;
        };
        return minWage.multiply(BigDecimal.valueOf(wages));
    }

    /** {@code rate} percent of the amount, to kopiykas. */
    public static BigDecimal share(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
