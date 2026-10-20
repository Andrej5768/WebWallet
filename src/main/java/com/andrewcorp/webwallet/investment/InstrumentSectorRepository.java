package com.andrewcorp.webwallet.investment;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface InstrumentSectorRepository extends JpaRepository<InstrumentSector, InstrumentSector.Key> {

    List<InstrumentSector> findByInstrumentIdIn(Collection<Long> instrumentIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from InstrumentSector s where s.instrumentId = :instrumentId")
    void deleteByInstrument(Long instrumentId);
}
