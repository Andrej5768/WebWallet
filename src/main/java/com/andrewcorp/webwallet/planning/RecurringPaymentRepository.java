package com.andrewcorp.webwallet.planning;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecurringPaymentRepository extends JpaRepository<RecurringPayment, Long> {

    Optional<RecurringPayment> findByIdAndUserId(Long id, Long userId);

    List<RecurringPayment> findByUserIdOrderByNameAscIdAsc(Long userId);

    /** Users with a recurring payment that may still have occurrences. */
    @Query("""
            select distinct r.userId from RecurringPayment r
            where r.endDate is null or r.endDate >= :since""")
    List<Long> findUsersWithActive(LocalDate since);
}
