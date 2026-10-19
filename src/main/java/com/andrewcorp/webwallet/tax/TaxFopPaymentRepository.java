package com.andrewcorp.webwallet.tax;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxFopPaymentRepository extends JpaRepository<TaxFopPayment, TaxFopPayment.Key> {

    List<TaxFopPayment> findByUserIdAndYear(Long userId, Integer year);
}
