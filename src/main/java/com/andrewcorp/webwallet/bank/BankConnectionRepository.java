package com.andrewcorp.webwallet.bank;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankConnectionRepository extends JpaRepository<BankConnection, Long> {

    Optional<BankConnection> findByUserIdAndProvider(Long userId, BankProvider provider);

    Optional<BankConnection> findByProviderAndWebhookSecret(BankProvider provider, String webhookSecret);

    List<BankConnection> findByProvider(BankProvider provider);
}
