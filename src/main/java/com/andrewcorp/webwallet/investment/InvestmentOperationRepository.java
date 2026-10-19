package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.ledger.account.Account;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvestmentOperationRepository extends JpaRepository<InvestmentOperation, Long> {

    Optional<InvestmentOperation> findByIdAndUserId(Long id, Long userId);

    /** All operations of the user with their instruments, for the portfolio. */
    @Query("""
            select o from InvestmentOperation o left join fetch o.instrument
            where o.userId = :userId order by o.date, o.id""")
    List<InvestmentOperation> findAllOfUser(Long userId);

    List<InvestmentOperation> findByAccountAndInstrument(Account account, Instrument instrument);

    /** Newest first, optionally of one account and one instrument. */
    @Query("""
            select o from InvestmentOperation o
            where o.userId = :userId
              and (:accountId is null or o.account.id = :accountId)
              and (:instrumentId is null or o.instrument.id = :instrumentId)""")
    Page<InvestmentOperation> search(Long userId, Long accountId, Long instrumentId, Pageable pageable);

    boolean existsByAccountId(Long accountId);

    /** The ids of the operations of the account in broker reports, to skip what was imported before. */
    @Query(
            "select o.externalId from InvestmentOperation o where o.account.id = :accountId and o.externalId is not null")
    Set<String> findExternalIds(Long accountId);

    /** The date of the first operation of the instrument, of anyone. */
    @Query("select min(o.date) from InvestmentOperation o where o.instrument.id = :instrumentId")
    LocalDate findFirstDate(Long instrumentId);

    /** The date of the first operation of anyone, how far back benchmarks are needed. */
    @Query("select min(o.date) from InvestmentOperation o")
    LocalDate findFirstDate();

    boolean existsByInstrumentId(Long instrumentId);

    boolean existsByInstrumentIdAndUserId(Long instrumentId, Long userId);
}
