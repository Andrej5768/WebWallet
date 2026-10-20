package com.andrewcorp.webwallet.investment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentDividendRepository extends JpaRepository<InstrumentDividend, Long> {

    Optional<InstrumentDividend> findByInstrumentIdAndExDate(Long instrumentId, LocalDate exDate);

    List<InstrumentDividend> findByInstrumentIdInOrderByExDate(Collection<Long> instrumentIds);
}
