package com.andrewcorp.webwallet.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andrewcorp.webwallet.investment.market.ListingSearch;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider.Listing;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PositionTimelineTest {

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

    private void trade(OperationType type, String date, String quantity) {
        InvestmentOperation operation = operation(type, date);
        operation.setQuantity(new BigDecimal(quantity));
        operation.setPrice(BigDecimal.TEN);
        operation.setAmount(new BigDecimal(quantity).multiply(BigDecimal.TEN));
    }

    private void split(String date, int from, int to) {
        InvestmentOperation split = operation(OperationType.SPLIT, date);
        split.setSplitFrom(from);
        split.setSplitTo(to);
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }

    @Test
    void tellsTheQuantityBeforeAndAfterADay() {
        trade(OperationType.BUY, "2026-01-10", "10");
        trade(OperationType.SELL, "2026-01-10", "4");
        split("2026-02-01", 1, 2);

        PositionTimeline timeline = new PositionTimeline(operations);

        assertThat(timeline.heldBefore(date("2026-01-10"))).isZero();
        assertThat(timeline.heldAfter(date("2026-01-10"))).isEqualByComparingTo("6");
        assertThat(timeline.heldBefore(date("2026-02-01"))).isEqualByComparingTo("6");
        assertThat(timeline.heldAfter(date("2026-03-01"))).isEqualByComparingTo("12");
    }

    @Test
    void sellsAWholeUnitAfterAReverseSplitIntoFractions() {
        trade(OperationType.BUY, "2026-01-10", "1");
        trade(OperationType.BUY, "2026-01-11", "1");
        trade(OperationType.BUY, "2026-01-12", "1");
        split("2026-02-01", 3, 1);
        trade(OperationType.SELL, "2026-03-01", "1");

        LotLedger ledger = LotLedger.of(operations);

        assertThat(ledger.quantity()).isZero();
        assertThat(ledger.sales()).hasSize(1);
        assertThat(new PositionTimeline(operations.subList(0, 4)).heldAfter(date("2026-02-01")))
                .isEqualByComparingTo("1");
    }

    @Test
    void valuesAnOversoldPositionLeniently() {
        trade(OperationType.BUY, "2026-01-10", "5");
        trade(OperationType.SELL, "2026-01-11", "8");

        assertThatThrownBy(() -> LotLedger.of(operations)).isInstanceOf(LotLedger.Shortfall.class);
        LotLedger lenient = LotLedger.lenient(operations);
        assertThat(lenient.quantity()).isZero();
        assertThat(lenient.sales()).hasSize(1);
    }

    @Test
    void computesCouponDatesWithoutSteppingThroughMonths() {
        Instrument bond = new Instrument(1L, "UA1", InstrumentType.GOVERNMENT_BOND, "UAH", Valuation.NOMINAL);
        bond.setNominal(new BigDecimal("1000"));
        bond.setCouponRate(new BigDecimal("12"));
        bond.setCouponFrequency(12);
        bond.setMaturityDate(date("2100-01-31"));

        long started = System.nanoTime();
        List<LocalDate> dates = BondValuation.couponDates(bond, date("2026-01-01"), date("2026-04-30"));
        BigDecimal price = BondValuation.price(bond, date("2026-02-14"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        // Monthly from the 31st: the last day of shorter months
        assertThat(dates)
                .containsExactly(date("2026-01-31"), date("2026-02-28"), date("2026-03-31"), date("2026-04-30"));
        // 10 a month, 14 of the 28 days since 31 January
        assertThat(price).isEqualByComparingTo("1005");
        assertThat(BondValuation.coupon(bond)).isEqualByComparingTo("10");

        bond.setCouponFrequency(5);
        assertThat(BondValuation.hasCoupons(bond)).isFalse();
        assertThat(BondValuation.couponDates(bond, date("2026-01-01"), date("2026-12-31")))
                .isEmpty();
    }

    @Test
    void keepsOnlyListingsTheDatabaseCanHold() {
        String longName = "N".repeat(300);
        List<Listing> listings = new ArrayList<>();
        listings.add(new Listing("AAPL", longName, "NASDAQ", "XNGS", "United States", "USD", "Common Stock"));
        listings.add(new Listing("NOCUR", "No currency", "X", "XNYS", "US", null, "Common Stock"));
        listings.add(new Listing("X".repeat(31), "Long", "X", "XNYS", "US", "USD", "Common Stock"));
        listings.add(new Listing("BAD", "Bad currency", "X", "XNYS", "US", "usd", "Common Stock"));
        listings.add(new Listing("MIC", "Long mic", "X", "XNYSXNYSXNYS", "US", "USD", "Common Stock"));

        List<Listing> kept = listings.stream()
                .map(listing -> ReflectionTestUtils.<Listing>invokeMethod(ListingSearch.class, "sanitized", listing))
                .filter(listing -> listing != null)
                .toList();

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).name()).hasSize(200);
    }
}
