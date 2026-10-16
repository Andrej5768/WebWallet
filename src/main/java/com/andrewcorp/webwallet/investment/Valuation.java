package com.andrewcorp.webwallet.investment;

/** How the price of an instrument is known. */
public enum Valuation {
    /** Daily closes from the market data provider. */
    MARKET,
    /** The nominal plus the coupon accrued since the last payment, for bonds held to maturity. */
    NOMINAL,
    /** A price entered by hand. */
    MANUAL
}
