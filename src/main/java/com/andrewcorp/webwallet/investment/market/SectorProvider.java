package com.andrewcorp.webwallet.investment.market;

import com.andrewcorp.webwallet.investment.Sector;
import java.math.BigDecimal;
import java.util.List;

/**
 * Source of the sectors of instruments, implemented with the dividends ({@code app.market-data.dividends-provider}),
 * as the same fundamental data service has both.
 */
public interface SectorProvider {

    /** Whether the provider is configured. */
    boolean enabled();

    /**
     * The sectors of a US listing: a company is in one, a fund in several. The weights are shares (0..1) and may add up
     * to less than one, e.g. for the cash of a fund; empty when the provider does not know the symbol.
     */
    List<SectorWeight> sectors(String symbol, boolean fund);

    record SectorWeight(Sector sector, BigDecimal weight) {}
}
