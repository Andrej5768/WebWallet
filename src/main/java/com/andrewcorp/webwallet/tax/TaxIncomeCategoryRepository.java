package com.andrewcorp.webwallet.tax;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TaxIncomeCategoryRepository extends JpaRepository<TaxIncomeCategory, TaxIncomeCategory.Key> {

    List<TaxIncomeCategory> findByUserId(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TaxIncomeCategory c where c.userId = :userId")
    void deleteAllOfUser(Long userId);
}
