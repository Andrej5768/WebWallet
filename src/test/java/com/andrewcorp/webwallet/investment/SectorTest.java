package com.andrewcorp.webwallet.investment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SectorTest {

    @Test
    void classifiesCompaniesByTheirIndustryFirst() {
        assertThat(Sector.ofCompany("TECHNOLOGY", "SERVICES-PREPACKAGED SOFTWARE"))
                .isEqualTo(Sector.INFORMATION_TECHNOLOGY);
        // An electric utility is not electronics
        assertThat(Sector.ofCompany("ENERGY & TRANSPORTATION", "ELECTRIC SERVICES"))
                .isEqualTo(Sector.UTILITIES);
        assertThat(Sector.ofCompany("FINANCE", "NATIONAL COMMERCIAL BANKS")).isEqualTo(Sector.FINANCIALS);
        assertThat(Sector.ofCompany("LIFE SCIENCES", "PHARMACEUTICAL PREPARATIONS"))
                .isEqualTo(Sector.HEALTH_CARE);
        assertThat(Sector.ofCompany("ENERGY & TRANSPORTATION", "PETROLEUM REFINING"))
                .isEqualTo(Sector.ENERGY);
        assertThat(Sector.ofCompany("MANUFACTURING", "BEVERAGES")).isEqualTo(Sector.CONSUMER_STAPLES);
        assertThat(Sector.ofCompany("TRADE & SERVICES", "RETAIL-VARIETY STORES"))
                .isEqualTo(Sector.CONSUMER_STAPLES);
        assertThat(Sector.ofCompany("MANUFACTURING", "MOTOR VEHICLES & PASSENGER CAR BODIES"))
                .isEqualTo(Sector.CONSUMER_DISCRETIONARY);
        assertThat(Sector.ofCompany("REAL ESTATE & CONSTRUCTION", "REAL ESTATE INVESTMENT TRUSTS"))
                .isEqualTo(Sector.REAL_ESTATE);
        // OIL is a word of its own, not a part of SOIL
        assertThat(Sector.ofCompany("MANUFACTURING", "SOIL PREPARATION SERVICES"))
                .isEqualTo(Sector.INDUSTRIALS);
        // Without a known industry the broad sector decides
        assertThat(Sector.ofCompany("LIFE SCIENCES", "SOMETHING ELSE")).isEqualTo(Sector.HEALTH_CARE);
        assertThat(Sector.ofCompany(null, null)).isNull();
    }

    @Test
    void readsTheNamesOfFundSectors() {
        assertThat(Sector.ofName("INFORMATION TECHNOLOGY")).isEqualTo(Sector.INFORMATION_TECHNOLOGY);
        assertThat(Sector.ofName("consumer_discretionary")).isEqualTo(Sector.CONSUMER_DISCRETIONARY);
        assertThat(Sector.ofName("Healthcare")).isEqualTo(Sector.HEALTH_CARE);
        assertThat(Sector.ofName("SOMETHING NEW")).isNull();
    }
}
