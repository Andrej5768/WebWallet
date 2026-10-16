package com.andrewcorp.webwallet.investment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The quantity of an instrument held over time, from its trades and splits: the one rule of the portfolio, the value
 * history and the dividend calendar. Quantities keep the 8 decimals of the database, so that a split into fractions
 * (e.g. three single shares merged 3 into 1) adds up to whole units again.
 */
public final class PositionTimeline {

    /** Quantities are stored with this many decimals; anything smaller is noise of a division. */
    public static final int SCALE = 8;

    /** Two quantities closer than this are the same. */
    public static final BigDecimal TOLERANCE = BigDecimal.ONE.movePointLeft(SCALE);

    private static final MathContext PRECISION = MathContext.DECIMAL64;

    /** The quantity at the end of each day with a change. */
    private final NavigableMap<LocalDate, BigDecimal> afterDay = new TreeMap<>();

    /** @param operations the operations of the instrument (on one account or several), in any order */
    public PositionTimeline(List<InvestmentOperation> operations) {
        BigDecimal held = BigDecimal.ZERO;
        for (InvestmentOperation operation : sorted(operations)) {
            BigDecimal next = apply(held, operation);
            if (next.compareTo(held) != 0 || afterDay.containsKey(operation.getDate())) {
                afterDay.put(operation.getDate(), next);
            }
            held = next;
        }
    }

    /** The quantity held at the start of the day: after the operations of the days before. */
    public BigDecimal heldBefore(LocalDate day) {
        Map.Entry<LocalDate, BigDecimal> entry = afterDay.lowerEntry(day);
        return entry != null ? entry.getValue() : BigDecimal.ZERO;
    }

    /** The quantity held at the end of the day. */
    public BigDecimal heldAfter(LocalDate day) {
        Map.Entry<LocalDate, BigDecimal> entry = afterDay.floorEntry(day);
        return entry != null ? entry.getValue() : BigDecimal.ZERO;
    }

    /** The quantity after one more operation. */
    static BigDecimal apply(BigDecimal held, InvestmentOperation operation) {
        return switch (operation.getType()) {
            case BUY -> held.add(operation.getQuantity());
            case SELL -> held.subtract(operation.getQuantity());
            case SPLIT -> round(held.multiply(splitFactor(operation), PRECISION));
            default -> held;
        };
    }

    /** New units per old unit of a split. */
    static BigDecimal splitFactor(InvestmentOperation split) {
        return BigDecimal.valueOf(split.getSplitTo()).divide(BigDecimal.valueOf(split.getSplitFrom()), PRECISION);
    }

    /** A quantity with the decimals of the database. */
    static BigDecimal round(BigDecimal quantity) {
        return quantity.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** By date, and by id (the order they were recorded in) within a day. */
    static List<InvestmentOperation> sorted(List<InvestmentOperation> operations) {
        return operations.stream()
                .sorted(Comparator.comparing(InvestmentOperation::getDate)
                        .thenComparing(operation -> operation.getId() != null ? operation.getId() : Long.MAX_VALUE))
                .toList();
    }
}
