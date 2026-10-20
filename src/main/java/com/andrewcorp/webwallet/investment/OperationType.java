package com.andrewcorp.webwallet.investment;

import java.math.BigDecimal;

public enum OperationType {
    BUY,
    SELL,
    DIVIDEND,
    COUPON,
    /** A fee of the broker that is not part of a trade, e.g. a custody fee. */
    FEE,
    SPLIT,
    /** Money brought to the account in any currency, e.g. by a card payment at the broker; counts as deposited. */
    DEPOSIT,
    /** Money taken from the account in any currency; counts as deposited, negatively. */
    WITHDRAWAL,
    /** A currency conversion: {@code amount} of the currency sold for {@code toAmount} of {@code toCurrency}. */
    EXCHANGE;

    /** How the operation changes the cash of the account in its currency: positive adds money. */
    public BigDecimal cash(BigDecimal amount, BigDecimal fee, BigDecimal tax) {
        return switch (this) {
            case BUY, EXCHANGE -> amount.add(fee).negate();
            case SELL -> amount.subtract(fee);
            case DIVIDEND, COUPON -> amount.subtract(tax);
            case FEE, WITHDRAWAL -> amount.negate();
            case DEPOSIT -> amount;
            case SPLIT -> BigDecimal.ZERO;
        };
    }

    /** Moves only money, never an instrument. */
    public boolean cashOnly() {
        return this == DEPOSIT || this == WITHDRAWAL || this == EXCHANGE;
    }
}
