package com.andrewcorp.webwallet.investment;

import java.math.BigDecimal;

public enum OperationType {
    BUY,
    SELL,
    DIVIDEND,
    COUPON,
    /** A fee of the broker that is not part of a trade, e.g. a custody fee. */
    FEE,
    SPLIT;

    /** How the operation changes the cash of the account, in its currency: positive adds money. */
    public BigDecimal cash(BigDecimal amount, BigDecimal fee, BigDecimal tax) {
        return switch (this) {
            case BUY -> amount.add(fee).negate();
            case SELL -> amount.subtract(fee);
            case DIVIDEND, COUPON -> amount.subtract(tax);
            case FEE -> amount.negate();
            case SPLIT -> BigDecimal.ZERO;
        };
    }
}
