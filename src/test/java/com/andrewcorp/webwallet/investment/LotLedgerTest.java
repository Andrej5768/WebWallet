package com.andrewcorp.webwallet.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LotLedgerTest {

    private final List<InvestmentOperation> operations = new ArrayList<>();
    private long nextId = 1;

    private InvestmentOperation operation(OperationType type, String date) {
        InvestmentOperation operation = new InvestmentOperation(1L);
        ReflectionTestUtils.setField(operation, "id", nextId++);
        operation.setType(type);
        operation.setDate(LocalDate.parse(date));
        operations.add(operation);
        return operation;
    }

    private void trade(OperationType type, String date, String quantity, String price, String fee) {
        InvestmentOperation operation = operation(type, date);
        operation.setQuantity(new BigDecimal(quantity));
        operation.setPrice(new BigDecimal(price));
        operation.setAmount(new BigDecimal(quantity).multiply(new BigDecimal(price)));
        operation.setFee(new BigDecimal(fee));
    }

    @Test
    void sellsTheOldestLotsFirst() {
        trade(OperationType.BUY, "2026-01-10", "10", "100", "1");
        trade(OperationType.BUY, "2026-06-10", "10", "200", "0");
        trade(OperationType.SELL, "2026-12-10", "15", "250", "2");

        LotLedger ledger = LotLedger.of(operations);

        // 10 at 100.10 (the fee is part of the cost) and 5 of the June lot at 200
        LotLedger.Sale sale = ledger.sales().get(0);
        assertThat(sale.cost()).isEqualByComparingTo("2001");
        assertThat(sale.proceeds()).isEqualByComparingTo("3748");
        assertThat(sale.realized()).isEqualByComparingTo("1747");
        assertThat(sale.parts())
                .extracting(LotLedger.Part::quantity)
                .containsExactly(new BigDecimal("10"), new BigDecimal("5"));
        assertThat(ledger.quantity()).isEqualByComparingTo("5");
        assertThat(ledger.averagePrice()).isEqualByComparingTo("200");
        assertThat(ledger.cost()).isEqualByComparingTo("1000");
        assertThat(ledger.lots())
                .extracting(LotLedger.LotView::remaining)
                .containsExactly(BigDecimal.ZERO, new BigDecimal("5"));
        assertThat(ledger.realized(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
                .isEqualByComparingTo("1747");
        assertThat(ledger.realized(LocalDate.parse("2027-01-01"), LocalDate.parse("2027-12-31")))
                .isZero();
    }

    @Test
    void splitsKeepTheCost() {
        trade(OperationType.BUY, "2026-01-10", "10", "100", "0");
        InvestmentOperation split = operation(OperationType.SPLIT, "2026-02-01");
        split.setSplitFrom(1);
        split.setSplitTo(4);
        trade(OperationType.SELL, "2026-03-01", "20", "30", "0");

        LotLedger ledger = LotLedger.of(operations);

        assertThat(ledger.quantity()).isEqualByComparingTo("20");
        assertThat(ledger.averagePrice()).isEqualByComparingTo("25");
        assertThat(ledger.sales().get(0).realized()).isEqualByComparingTo("100");
    }

    @Test
    void rejectsSellingMoreThanHeld() {
        trade(OperationType.BUY, "2026-01-10", "10", "100", "0");
        trade(OperationType.SELL, "2026-01-09", "5", "100", "0");

        assertThatThrownBy(() -> LotLedger.of(operations)).isInstanceOf(LotLedger.Shortfall.class);
    }

    @Test
    void bondsAreWorthTheNominalPlusTheAccruedCoupon() {
        Instrument bond = new Instrument(1L, "UA4000227045", InstrumentType.GOVERNMENT_BOND, "UAH", Valuation.NOMINAL);
        bond.setNominal(new BigDecimal("1000"));
        bond.setCouponRate(new BigDecimal("15.2"));
        bond.setCouponFrequency(2);
        bond.setMaturityDate(LocalDate.parse("2027-07-22"));

        // 86 of the 184 days since the coupon of 22 July: 76 * 86 / 184
        assertThat(BondValuation.price(bond, LocalDate.parse("2026-10-16"))).isEqualByComparingTo("1035.521739");
        assertThat(BondValuation.price(bond, LocalDate.parse("2027-07-22"))).isEqualByComparingTo("1000");
        bond.setCouponRate(null);
        assertThat(BondValuation.price(bond, LocalDate.parse("2026-10-16"))).isEqualByComparingTo("1000");
    }
}
