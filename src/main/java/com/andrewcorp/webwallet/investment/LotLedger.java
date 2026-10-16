package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.util.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The lots of one instrument on one account: every purchase is a lot, a sale takes the oldest lots first (FIFO). The
 * cost of a lot includes the fee of its purchase; the proceeds of a sale are without its fee. A split changes the
 * quantity and the unit cost of the open lots, not their cost. Amounts are in the currency of the operations.
 */
public final class LotLedger {

    private static final MathContext PRECISION = MathContext.DECIMAL64;

    private final List<Lot> lots = new ArrayList<>();
    private final List<Sale> sales = new ArrayList<>();
    private final boolean lenient;

    private LotLedger(boolean lenient) {
        this.lenient = lenient;
    }

    /**
     * Replays the operations by date (and id within a day).
     *
     * @throws Shortfall when a sale takes more than is held on its date
     */
    public static LotLedger of(List<InvestmentOperation> operations) {
        return replay(operations, false);
    }

    /**
     * Like {@link #of}, but a sale of more than is held takes what there is. For valuing data stored before the check
     * (or by two sales at once), which must not make the accounts unreadable.
     */
    public static LotLedger lenient(List<InvestmentOperation> operations) {
        return replay(operations, true);
    }

    private static LotLedger replay(List<InvestmentOperation> operations, boolean lenient) {
        LotLedger ledger = new LotLedger(lenient);
        PositionTimeline.sorted(operations).forEach(ledger::apply);
        return ledger;
    }

    private void apply(InvestmentOperation operation) {
        switch (operation.getType()) {
            case BUY ->
                lots.add(new Lot(
                        operation.getId(),
                        operation.getDate(),
                        operation.getQuantity(),
                        operation.getAmount().add(operation.getFee()).divide(operation.getQuantity(), PRECISION)));
            case SELL -> sell(operation);
            case SPLIT -> {
                BigDecimal factor = PositionTimeline.splitFactor(operation);
                lots.forEach(lot -> lot.split(factor));
            }
            default -> {}
        }
    }

    private void sell(InvestmentOperation operation) {
        BigDecimal left = operation.getQuantity();
        // A split into fractions may leave a held quantity a hair below a whole one
        if (!lenient && left.compareTo(quantity().add(PositionTimeline.TOLERANCE)) > 0) {
            throw new Shortfall(operation.getDate(), quantity());
        }
        List<Part> parts = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        for (Lot lot : lots) {
            if (left.signum() == 0) {
                break;
            }
            if (lot.remaining.signum() == 0) {
                continue;
            }
            BigDecimal taken = left.min(lot.remaining);
            lot.remaining = lot.remaining.subtract(taken);
            left = left.subtract(taken);
            if (left.compareTo(PositionTimeline.TOLERANCE) <= 0) {
                left = BigDecimal.ZERO;
            }
            parts.add(new Part(lot.operationId, lot.date, taken, lot.unitCost));
            cost = cost.add(taken.multiply(lot.unitCost, PRECISION));
        }
        BigDecimal proceeds = operation.getAmount().subtract(operation.getFee());
        sales.add(new Sale(
                operation.getId(),
                operation.getDate(),
                operation.getQuantity(),
                proceeds,
                Money.round4(cost),
                Money.round4(proceeds.subtract(cost)),
                List.copyOf(parts)));
    }

    /** Quantity held. */
    public BigDecimal quantity() {
        return lots.stream().map(lot -> lot.remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Cost of the quantity held: the open parts of the lots. */
    public BigDecimal cost() {
        return Money.round4(lots.stream()
                .map(lot -> lot.remaining.multiply(lot.unitCost, PRECISION))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Average cost of a unit held, null when nothing is held. */
    public BigDecimal averagePrice() {
        BigDecimal quantity = quantity();
        return quantity.signum() == 0 ? null : cost().divide(quantity, 6, RoundingMode.HALF_UP);
    }

    /** Profit of the sales in the period (FIFO), both ends inclusive. */
    public BigDecimal realized(LocalDate from, LocalDate to) {
        return sales.stream()
                .filter(sale -> !sale.date().isBefore(from) && !sale.date().isAfter(to))
                .map(Sale::realized)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<LotView> lots() {
        return lots.stream()
                .map(lot -> new LotView(lot.operationId, lot.date, lot.quantity, lot.remaining, lot.unitCost))
                .toList();
    }

    public List<Sale> sales() {
        return List.copyOf(sales);
    }

    private static final class Lot {

        private final Long operationId;
        private final LocalDate date;
        private BigDecimal quantity;
        private BigDecimal remaining;
        private BigDecimal unitCost;

        Lot(Long operationId, LocalDate date, BigDecimal quantity, BigDecimal unitCost) {
            this.operationId = operationId;
            this.date = date;
            this.quantity = quantity;
            this.remaining = quantity;
            this.unitCost = unitCost;
        }

        void split(BigDecimal factor) {
            quantity = PositionTimeline.round(quantity.multiply(factor, PRECISION));
            remaining = PositionTimeline.round(remaining.multiply(factor, PRECISION));
            unitCost = unitCost.divide(factor, PRECISION);
        }
    }

    /**
     * A purchase. Quantities and the unit cost are after the splits since then.
     *
     * @param remaining what is not sold yet; zero for a closed lot
     */
    public record LotView(
            Long operationId, LocalDate date, BigDecimal quantity, BigDecimal remaining, BigDecimal unitCost) {}

    /**
     * @param proceeds the amount of the sale without its fee
     * @param cost     the cost of the lots sold
     * @param parts    the lots it took, oldest first
     */
    public record Sale(
            Long operationId,
            LocalDate date,
            BigDecimal quantity,
            BigDecimal proceeds,
            BigDecimal cost,
            BigDecimal realized,
            List<Part> parts) {}

    /** The part of a lot a sale took. */
    public record Part(Long lotOperationId, LocalDate lotDate, BigDecimal quantity, BigDecimal unitCost) {}

    /** A sale of more than is held. */
    public static final class Shortfall extends RuntimeException {

        private final transient LocalDate date;
        private final transient BigDecimal held;

        Shortfall(LocalDate date, BigDecimal held) {
            super("Sale of more than held on " + date);
            this.date = date;
            this.held = held;
        }

        public LocalDate date() {
            return date;
        }

        public BigDecimal held() {
            return held;
        }
    }
}
