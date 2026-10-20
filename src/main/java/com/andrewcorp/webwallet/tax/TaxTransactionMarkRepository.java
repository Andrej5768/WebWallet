package com.andrewcorp.webwallet.tax;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxTransactionMarkRepository extends JpaRepository<TaxTransactionMark, Long> {

    List<TaxTransactionMark> findByUserId(Long userId);
}
