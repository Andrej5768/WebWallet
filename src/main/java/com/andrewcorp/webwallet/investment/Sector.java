package com.andrewcorp.webwallet.investment;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 11 sectors of GICS. Providers name sectors their own way: the sectors of a fund come close to GICS, a company
 * is classified by its SEC industry (SIC), which is matched by keywords, then by its broad sector.
 */
public enum Sector {
    ENERGY,
    MATERIALS,
    INDUSTRIALS,
    CONSUMER_DISCRETIONARY,
    CONSUMER_STAPLES,
    HEALTH_CARE,
    FINANCIALS,
    INFORMATION_TECHNOLOGY,
    COMMUNICATION_SERVICES,
    UTILITIES,
    REAL_ESTATE;

    /** Keywords of SIC industries, checked in this order (an electric utility is not electronics). */
    private static final List<Map.Entry<String, Sector>> INDUSTRY_WORDS = List.of(
            Map.entry("REAL ESTATE", REAL_ESTATE),
            Map.entry("REIT", REAL_ESTATE),
            Map.entry("ELECTRIC SERVICES", UTILITIES),
            Map.entry("ELECTRIC & OTHER SERVICES", UTILITIES),
            Map.entry("GAS TRANSMISSION", UTILITIES),
            Map.entry("NATURAL GAS DISTRIBUTION", UTILITIES),
            Map.entry("WATER SUPPLY", UTILITIES),
            Map.entry("COGENERATION", UTILITIES),
            Map.entry("BANK", FINANCIALS),
            Map.entry("INSURANCE", FINANCIALS),
            Map.entry("SECURITY BROKERS", FINANCIALS),
            Map.entry("INVESTMENT", FINANCIALS),
            Map.entry("FINANCE", FINANCIALS),
            Map.entry("CREDIT", FINANCIALS),
            Map.entry("SAVINGS INSTITUTION", FINANCIALS),
            Map.entry("PHARMACEUTICAL", HEALTH_CARE),
            Map.entry("BIOLOGICAL", HEALTH_CARE),
            Map.entry("MEDICAL", HEALTH_CARE),
            Map.entry("SURGICAL", HEALTH_CARE),
            Map.entry("HEALTH", HEALTH_CARE),
            Map.entry("HOSPITAL", HEALTH_CARE),
            Map.entry("DIAGNOSTIC", HEALTH_CARE),
            Map.entry("TELEPHONE", COMMUNICATION_SERVICES),
            Map.entry("COMMUNICATIONS SERVICES", COMMUNICATION_SERVICES),
            Map.entry("RADIOTELEPHONE", COMMUNICATION_SERVICES),
            Map.entry("BROADCASTING", COMMUNICATION_SERVICES),
            Map.entry("CABLE", COMMUNICATION_SERVICES),
            Map.entry("MOTION PICTURE", COMMUNICATION_SERVICES),
            Map.entry("ADVERTISING", COMMUNICATION_SERVICES),
            Map.entry("PUBLISHING", COMMUNICATION_SERVICES),
            Map.entry("NEWSPAPERS", COMMUNICATION_SERVICES),
            Map.entry("SOFTWARE", INFORMATION_TECHNOLOGY),
            Map.entry("COMPUTER", INFORMATION_TECHNOLOGY),
            Map.entry("SEMICONDUCTOR", INFORMATION_TECHNOLOGY),
            Map.entry("ELECTRONIC", INFORMATION_TECHNOLOGY),
            Map.entry("DATA PROCESSING", INFORMATION_TECHNOLOGY),
            Map.entry("CRUDE PETROLEUM", ENERGY),
            Map.entry("PETROLEUM", ENERGY),
            Map.entry("OIL", ENERGY),
            Map.entry("COAL", ENERGY),
            Map.entry("CHEMICAL", MATERIALS),
            Map.entry("STEEL", MATERIALS),
            Map.entry("METAL", MATERIALS),
            Map.entry("MINING", MATERIALS),
            Map.entry("GOLD", MATERIALS),
            Map.entry("PAPER", MATERIALS),
            Map.entry("PLASTIC", MATERIALS),
            Map.entry("GLASS", MATERIALS),
            Map.entry("LUMBER", MATERIALS),
            Map.entry("FOOD", CONSUMER_STAPLES),
            Map.entry("BEVERAGE", CONSUMER_STAPLES),
            Map.entry("BOTTLED", CONSUMER_STAPLES),
            Map.entry("TOBACCO", CONSUMER_STAPLES),
            Map.entry("CIGARETTE", CONSUMER_STAPLES),
            Map.entry("GROCERY", CONSUMER_STAPLES),
            Map.entry("SOAP", CONSUMER_STAPLES),
            Map.entry("PERFUME", CONSUMER_STAPLES),
            Map.entry("VARIETY STORES", CONSUMER_STAPLES),
            Map.entry("RETAIL", CONSUMER_DISCRETIONARY),
            Map.entry("MOTOR VEHICLE", CONSUMER_DISCRETIONARY),
            Map.entry("AUTO", CONSUMER_DISCRETIONARY),
            Map.entry("HOTEL", CONSUMER_DISCRETIONARY),
            Map.entry("EATING", CONSUMER_DISCRETIONARY),
            Map.entry("RESTAURANT", CONSUMER_DISCRETIONARY),
            Map.entry("APPAREL", CONSUMER_DISCRETIONARY),
            Map.entry("FOOTWEAR", CONSUMER_DISCRETIONARY),
            Map.entry("HOUSEHOLD", CONSUMER_DISCRETIONARY),
            Map.entry("AMUSEMENT", CONSUMER_DISCRETIONARY),
            Map.entry("AIRCRAFT", INDUSTRIALS),
            Map.entry("AIR TRANSPORTATION", INDUSTRIALS),
            Map.entry("RAILROAD", INDUSTRIALS),
            Map.entry("TRUCKING", INDUSTRIALS),
            Map.entry("MACHINERY", INDUSTRIALS),
            Map.entry("CONSTRUCTION", INDUSTRIALS),
            Map.entry("ENGINEERING", INDUSTRIALS),
            Map.entry("INDUSTRIAL", INDUSTRIALS),
            Map.entry("ELECTRICAL", INDUSTRIALS),
            Map.entry("ORDNANCE", INDUSTRIALS),
            Map.entry("GUIDED MISSILES", INDUSTRIALS));

    /** Broad sectors: SEC offices and the usual sector names of data providers. */
    private static final Map<String, Sector> SECTOR_NAMES = Map.ofEntries(
            Map.entry("TECHNOLOGY", INFORMATION_TECHNOLOGY),
            Map.entry("LIFE SCIENCES", HEALTH_CARE),
            Map.entry("HEALTHCARE", HEALTH_CARE),
            Map.entry("FINANCE", FINANCIALS),
            Map.entry("FINANCIAL SERVICES", FINANCIALS),
            Map.entry("REAL ESTATE & CONSTRUCTION", REAL_ESTATE),
            Map.entry("ENERGY & TRANSPORTATION", ENERGY),
            Map.entry("MANUFACTURING", INDUSTRIALS),
            Map.entry("TRADE & SERVICES", CONSUMER_DISCRETIONARY),
            Map.entry("CONSUMER CYCLICAL", CONSUMER_DISCRETIONARY),
            Map.entry("CONSUMER DEFENSIVE", CONSUMER_STAPLES),
            Map.entry("BASIC MATERIALS", MATERIALS),
            Map.entry("COMMUNICATION", COMMUNICATION_SERVICES),
            Map.entry("TELECOMMUNICATION SERVICES", COMMUNICATION_SERVICES));

    /** A sector as GICS names it, in any case and with spaces or underscores; null when unknown. */
    public static Sector ofName(String name) {
        if (name == null) {
            return null;
        }
        String key = normalized(name);
        for (Sector sector : values()) {
            if (sector.name().replace('_', ' ').equals(key)) {
                return sector;
            }
        }
        return SECTOR_NAMES.get(key);
    }

    /** The sector of a company from its industry, else from its broad sector; null when neither tells. */
    public static Sector ofCompany(String sector, String industry) {
        if (industry != null) {
            // Words from their start, so that OIL is not in SOIL
            String words = " " + normalized(industry).replaceAll("[^A-Z&]+", " ") + " ";
            for (Map.Entry<String, Sector> entry : INDUSTRY_WORDS) {
                if (words.contains(" " + entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return ofName(sector);
    }

    private static String normalized(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    }
}
