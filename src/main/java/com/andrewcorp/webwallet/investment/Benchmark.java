package com.andrewcorp.webwallet.investment;

/** Index funds the portfolio can be compared with. */
public enum Benchmark {
    SP500("SPY"),
    MSCI_WORLD("URTH"),
    NASDAQ100("QQQ");

    private final String symbol;

    Benchmark(String symbol) {
        this.symbol = symbol;
    }

    /** The ticker of the fund at the market data provider, listed in the United States. */
    public String symbol() {
        return symbol;
    }
}
