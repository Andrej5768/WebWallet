package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.investment.market.MarketDataProvider;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider.DailyClose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily closes of the market instruments someone holds. Each instrument is checked a few times a day; a new one
 * loads a year of history, so that its chart and returns have data at once.
 */
@Service
public class PriceService {

    /** How long after a check an instrument is checked again. */
    static final Duration CHECK_INTERVAL = Duration.ofHours(6);

    /** How much history a new instrument loads. */
    static final int HISTORY_DAYS = 366;

    private final InstrumentRepository instruments;
    private final InstrumentPriceRepository prices;
    private final InvestmentOperationRepository operations;
    private final HistoryRepository history;
    private final MarketDataProvider provider;
    private final Clock clock;

    public PriceService(
            InstrumentRepository instruments,
            InstrumentPriceRepository prices,
            InvestmentOperationRepository operations,
            HistoryRepository history,
            MarketDataProvider provider,
            Clock clock) {
        this.instruments = instruments;
        this.prices = prices;
        this.operations = operations;
        this.history = history;
        this.provider = provider;
        this.clock = clock;
    }

    /** Ids of the instruments to check now, at most {@code limit}, those never checked first. */
    @Transactional(readOnly = true)
    public List<Long> due(int limit) {
        if (!provider.enabled()) {
            return List.of();
        }
        return instruments.findDueForPrices(Instant.now(clock).minus(CHECK_INTERVAL), Pageable.ofSize(limit)).stream()
                .map(Instrument::getId)
                .toList();
    }

    /**
     * Loads the closes from the last known one on (which may have been taken during the session), and the older ones
     * back to the first operation with the instrument (for a benchmark: the first deposit or operation of anyone), at
     * least a year. The check is recorded even when nothing is new.
     */
    @Transactional
    public void update(Long instrumentId) {
        Instrument instrument = instruments.findById(instrumentId).orElseThrow();
        instrument.setPricesCheckedAt(Instant.now(clock));
        LocalDate today = LocalDate.now(clock);
        LocalDate needed = today.minusDays(HISTORY_DAYS);
        LocalDate firstOperation =
                instrument.isBenchmark() ? operations.findFirstDate() : operations.findFirstDate(instrumentId);
        if (firstOperation != null && firstOperation.isBefore(needed)) {
            needed = firstOperation;
        }
        // A benchmark buys the deposits of the brokerage accounts, also those before the first trade
        LocalDate firstDeposit = instrument.isBenchmark() ? history.findFirstBrokerageDate() : null;
        if (firstDeposit != null && firstDeposit.isBefore(needed)) {
            needed = firstDeposit;
        }
        LocalDate first = prices.findFirstDate(instrumentId).orElse(null);
        if (first == null) {
            load(instrument, needed, today);
            return;
        }
        if (needed.isBefore(first)) {
            load(instrument, needed, first.minusDays(1));
        }
        // The last close again: loaded during a trading session, it was the price of the moment, not the close
        load(instrument, prices.findLastDate(instrumentId).orElseThrow(), today);
    }

    private void load(Instrument instrument, LocalDate from, LocalDate to) {
        for (DailyClose close : provider.closes(instrument.getSymbol(), instrument.getMicCode(), from, to)) {
            if (close.close() != null && close.close().signum() > 0) {
                prices.save(new InstrumentPrice(instrument.getId(), close.date(), close.close()));
            }
        }
    }

    /** Marks the instrument as checked, after a failure that rolled the update back. */
    @Transactional
    public void markChecked(Long instrumentId) {
        instruments.findById(instrumentId).ifPresent(instrument -> instrument.setPricesCheckedAt(Instant.now(clock)));
    }
}
