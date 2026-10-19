package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.investment.market.SectorProvider;
import com.andrewcorp.webwallet.investment.market.SectorProvider.SectorWeight;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sectors of the held market instruments, asked for rarely: they hardly change. */
@Service
public class SectorService {

    static final Duration CHECK_INTERVAL = Duration.ofDays(90);

    private final Optional<SectorProvider> provider;
    private final InstrumentRepository instruments;
    private final InstrumentSectorRepository sectors;
    private final Clock clock;

    public SectorService(
            Optional<SectorProvider> provider,
            InstrumentRepository instruments,
            InstrumentSectorRepository sectors,
            Clock clock) {
        this.provider = provider;
        this.instruments = instruments;
        this.sectors = sectors;
        this.clock = clock;
    }

    /** Instruments whose sectors are due, at most {@code limit}; none without a provider. */
    @Transactional(readOnly = true)
    public List<Long> due(int limit) {
        if (provider.isEmpty() || !provider.get().enabled()) {
            return List.of();
        }
        return instruments.findDueForSectors(Instant.now(clock).minus(CHECK_INTERVAL), Pageable.ofSize(limit)).stream()
                .map(Instrument::getId)
                .toList();
    }

    /** Replaces the sectors of the instrument with those the provider reports; none when it does not know them. */
    @Transactional
    public void update(Long instrumentId) {
        Instrument instrument = instruments.findById(instrumentId).orElseThrow();
        List<SectorWeight> weights =
                provider.orElseThrow().sectors(instrument.getSymbol(), instrument.getType() != InstrumentType.STOCK);
        sectors.deleteByInstrument(instrumentId);
        for (SectorWeight weight : weights) {
            if (weight.weight().signum() > 0) {
                sectors.save(new InstrumentSector(instrumentId, weight.sector(), weight.weight()));
            }
        }
        // The delete cleared the persistence context: the instrument is read again
        instruments.findById(instrumentId).orElseThrow().setSectorsCheckedAt(Instant.now(clock));
    }

    @Transactional
    public void markChecked(Long instrumentId) {
        instruments.findById(instrumentId).ifPresent(instrument -> instrument.setSectorsCheckedAt(Instant.now(clock)));
    }
}
