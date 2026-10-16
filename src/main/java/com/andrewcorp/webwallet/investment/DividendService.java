package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.investment.market.DividendProvider;
import com.andrewcorp.webwallet.investment.market.DividendProvider.Dividend;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the dividends of the held US instruments, each checked once a week. */
@Service
public class DividendService {

    /** How long after a check an instrument is checked again; dividends are declared weeks ahead. */
    static final Duration CHECK_INTERVAL = Duration.ofDays(7);

    private final InstrumentRepository instruments;
    private final InstrumentDividendRepository dividends;
    private final DividendProvider provider;
    private final Clock clock;

    public DividendService(
            InstrumentRepository instruments,
            InstrumentDividendRepository dividends,
            DividendProvider provider,
            Clock clock) {
        this.instruments = instruments;
        this.dividends = dividends;
        this.provider = provider;
        this.clock = clock;
    }

    /** Ids of the instruments to check now, none when the provider is not configured. */
    @Transactional(readOnly = true)
    public List<Long> due(int limit) {
        if (!provider.enabled()) {
            return List.of();
        }
        return instruments
                .findDueForDividends(Instant.now(clock).minus(CHECK_INTERVAL), Pageable.ofSize(limit))
                .stream()
                .map(Instrument::getId)
                .toList();
    }

    /** Stores the dividends the provider knows, updating those already stored (declared ones may change). */
    @Transactional
    public void update(Long instrumentId) {
        Instrument instrument = instruments.findById(instrumentId).orElseThrow();
        instrument.setDividendsCheckedAt(Instant.now(clock));
        for (Dividend dividend : provider.dividends(instrument.getSymbol())) {
            InstrumentDividend stored = dividends
                    .findByInstrumentIdAndExDate(instrumentId, dividend.exDate())
                    .orElseGet(() -> new InstrumentDividend(instrumentId, dividend.exDate()));
            stored.setPaymentDate(dividend.paymentDate());
            stored.setDeclarationDate(dividend.declarationDate());
            stored.setRecordDate(dividend.recordDate());
            stored.setAmount(dividend.amount());
            dividends.save(stored);
        }
    }

    @Transactional
    public void markChecked(Long instrumentId) {
        instruments
                .findById(instrumentId)
                .ifPresent(instrument -> instrument.setDividendsCheckedAt(Instant.now(clock)));
    }
}
