package com.andrewcorp.webwallet.budget;

import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BudgetRepository extends JpaRepository<BudgetAssignment, Long> {

    @Query("select a from BudgetAssignment a join fetch a.category where a.userId = :userId and a.month <= :month")
    List<BudgetAssignment> findUpTo(Long userId, LocalDate month);

    @Query("select a from BudgetAssignment a join fetch a.category where a.userId = :userId and a.month = :month")
    List<BudgetAssignment> findByMonth(Long userId, LocalDate month);

    @Query("""
            select a from BudgetAssignment a join fetch a.category
            where a.userId = :userId and a.month between :from and :to""")
    List<BudgetAssignment> findBetween(Long userId, LocalDate from, LocalDate to);

    Optional<BudgetAssignment> findByUserIdAndMonthAndCategory(Long userId, LocalDate month, Category category);

    boolean existsByCategoryIdIn(Collection<Long> categoryIds);

    boolean existsByUserId(Long userId);

    /** Expense and income parts of transactions on budget accounts, summed per day, currency and category. */
    @Query("""
            select t.date as date, a.currency as currency, t.type as type, c.id as categoryId, c.kind as kind,
                   sum(s.amount) as amount
            from TransactionSplit s join s.transaction t join t.account a left join s.category c
            where t.userId = :userId and a.inBudget = true and t.type <> :transfer and t.adjustment = false
              and t.date <= :until
            group by t.date, a.currency, t.type, c.id, c.kind""")
    List<SplitRow> findBudgetSplits(Long userId, LocalDate until, TransactionType transfer);

    /** Like {@link #findBudgetSplits}, limited to the days from {@code from} to {@code until}. */
    @Query("""
            select t.date as date, a.currency as currency, t.type as type, c.id as categoryId, c.kind as kind,
                   sum(s.amount) as amount
            from TransactionSplit s join s.transaction t join t.account a left join s.category c
            where t.userId = :userId and a.inBudget = true and t.type <> :transfer and t.adjustment = false
              and t.date between :from and :until
            group by t.date, a.currency, t.type, c.id, c.kind""")
    List<SplitRow> findBudgetSplitsBetween(Long userId, LocalDate from, LocalDate until, TransactionType transfer);

    /** Transfers between a budget account and an account outside the budget, summed per day and currencies. */
    @Query("""
            select t.date as date, a.currency as currency, a.inBudget as fromBudget, sum(t.amount) as amount,
                   ta.currency as toCurrency, sum(t.toAmount) as toAmount
            from Transaction t join t.account a join t.toAccount ta
            where t.userId = :userId and t.type = :transfer and a.inBudget <> ta.inBudget and t.date <= :until
            group by t.date, a.currency, a.inBudget, ta.currency""")
    List<TransferRow> findBudgetBoundaryTransfers(Long userId, LocalDate until, TransactionType transfer);

    interface SplitRow {
        LocalDate getDate();

        String getCurrency();

        TransactionType getType();

        Long getCategoryId();

        CategoryKind getKind();

        BigDecimal getAmount();
    }

    interface TransferRow {
        LocalDate getDate();

        String getCurrency();

        boolean getFromBudget();

        BigDecimal getAmount();

        String getToCurrency();

        BigDecimal getToAmount();
    }
}
