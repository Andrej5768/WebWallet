package com.andrewcorp.webwallet.planning;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecurringOccurrenceRepository extends JpaRepository<RecurringOccurrence, Long> {

    Optional<RecurringOccurrence> findByRecurringAndDueDate(RecurringPayment recurring, LocalDate dueDate);

    /** Stored occurrences of the user's recurring payments in the period. */
    @Query("""
            select o from RecurringOccurrence o join fetch o.recurring r
            where r.userId = :userId and o.dueDate between :from and :to""")
    List<RecurringOccurrence> findByUser(Long userId, LocalDate from, LocalDate to);

    /** Occurrences paid by the transactions, to show which recurring payment a transaction belongs to. */
    @Query("""
            select o from RecurringOccurrence o join fetch o.recurring
            where o.transaction.id in :transactionIds""")
    List<RecurringOccurrence> findByTransactionIds(Collection<Long> transactionIds);

    /** Transactions of the user that already pay an occurrence. */
    @Query("""
            select o.transaction.id from RecurringOccurrence o
            where o.recurring.userId = :userId and o.transaction is not null""")
    Set<Long> findLinkedTransactionIds(Long userId);
}
